/*
 * Copyright (C) 2016-2025 phantombot.github.io/PhantomBot
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.gmt2001.twitch.tmi.processors;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.gmt2001.twitch.tmi.TMIMessage;
import com.gmt2001.twitch.tmi.TMISlashCommands;
import com.gmt2001.util.concurrent.ExecutorService;

import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import tv.phantombot.CaselessProperties;
import tv.phantombot.PhantomBot;
import tv.phantombot.event.EventBus;
import tv.phantombot.event.command.CommandEvent;
import tv.phantombot.event.irc.channel.IrcChannelUserModeEvent;
import tv.phantombot.event.irc.message.IrcChannelMessageEvent;
import tv.phantombot.event.irc.message.IrcModerationEvent;
import tv.phantombot.event.irc.message.IrcModerationEvent.ModerationActions.Actions;
import tv.phantombot.event.irc.message.IrcPrivateMessageEvent;
import tv.phantombot.event.twitch.bits.TwitchBitsEvent;

/**
 * Handles the PRIVMSG IRC command and tracks moderators via IRCv3 item.tags()
 *
 * @author gmt2001
 */
public final class PrivMsgTMIProcessor extends AbstractTMIProcessor {

    private final List<String> moderators = new CopyOnWriteArrayList<>();
    private static final Map<String, Instant> selfMessages = new ConcurrentHashMap<>();

    static {
        ExecutorService.scheduleAtFixedRate(()->{
            final Instant now = Instant.now();
            selfMessages.forEach((k,v) -> {
                if (v.isBefore(now)) {
                    selfMessages.remove(k);
                }
            });
        }, 10, 10, TimeUnit.SECONDS);
    }

    public PrivMsgTMIProcessor() {
        super("PRIVMSG");
    }

    public static String stripAction(String message) {
        if (message.charAt(0) == (char) 1 && message.startsWith("ACTION", 1)) {
            message = "/me " + message.substring(7).trim();
        }

        return message;
    }

    /**
     * Caches a message GUID for 10 seconds to prevent self-triggering.
     * 
     * @param messageid The message GUID to cache.
     */
    public static void preventSelfTrigger(String messageid) {
        selfMessages.computeIfAbsent(messageid, k -> Instant.now().plusSeconds(10));
    }

    @Override
    protected void onMessage(TMIMessage item) {
        if (item.nick().equalsIgnoreCase(PhantomBot.instance().getBotName())) {
            Mono.delay(Duration.ofSeconds(1)).block();
        }
        if (item.tags().containsKey("id") && selfMessages.containsKey(item.tags().get("id"))) {
            return;
        }
        
        String message = item.parameters();

        message = stripAction(message);

        /**
         * @botproperty printtwitchchattoconsole - If `true`, Twitch chat is printed to the console. Default `true`
         * @botpropertycatsort printtwitchchattoconsole 900 20 Twitch
         */
        if (CaselessProperties.instance().getPropertyAsBoolean("printtwitchchattoconsole", true)) {
            com.gmt2001.Console.out.println(item.nick() + ": " + message);
        }

        com.gmt2001.Console.debug.println("IRCv3 Tags: " + item.tags());

        if (item.tags().containsKey("source-room-id") && !item.tags().get("source-room-id").equals(item.tags().get("room-id"))) {
            com.gmt2001.Console.debug.println("Ignored due to source-room-id != room-id");
            return;
        }

        if (!item.nick().equalsIgnoreCase(this.user())) {
            if (item.tags().get("mod").equals("1") || !item.tags().get("user-type").isEmpty()) {
                if (!this.moderators.contains(item.nick())) {
                    EventBus.instance().postAsync(new IrcChannelUserModeEvent(this.session(), item.nick(), "O", true));
                    this.moderators.add(item.nick());
                }
            } else {
                if (this.moderators.contains(item.nick())) {
                    EventBus.instance().postAsync(new IrcChannelUserModeEvent(this.session(), item.nick(), "O", false));
                    this.moderators.remove(item.nick());
                }
            }
        }

        IrcModerationEvent modEvent = new IrcModerationEvent(this.session(), item.nick(), message, item.tags(), item);

        EventBus.instance().postAsync(modEvent);

        final String fmessage = message;
        modEvent.mono().timeout(Duration.ofSeconds(5)).onErrorReturn(Boolean.FALSE).doOnSuccess(moderated -> {
            try {
                if (moderated) {
                    com.gmt2001.Console.debug.println("Message was moderated");
                    return;
                }
                
                if (item.tags().containsKey("subscriber") && item.tags().get("subscriber").equals("1")) {
                    EventBus.instance().postAsync(new IrcPrivateMessageEvent(this.session(), "jtv",
                            "SPECIALUSER " + item.nick() + " subscriber", item.tags()));
                }

                if (item.tags().containsKey("bits")) {
                    EventBus.instance().postAsync(new TwitchBitsEvent(item.nick(), item.tags().get("bits"), fmessage));
                }

                if (CommandEvent.isCommand(item)) {
                    EventBus.instance().postAsync(CommandEvent.asCommand(item.nick(), fmessage, item.tags()));
                }

                EventBus.instance()
                        .postAsync(new IrcChannelMessageEvent(this.session(), item.nick(), fmessage, item.tags(), item));
            } catch (Exception ex) {
                Map<String,Object> data = Map.of(
                    "nick", item.nick() == null ? ">>null" : item.nick(),
                    "message", fmessage == null ? ">>null" : fmessage);
                com.gmt2001.Console.err.printStackTrace(ex, data, false);
            }
        }).subscribe();

        modEvent.completedMono().doFinally(sig -> {
            if (sig == SignalType.ON_COMPLETE) {
                IrcModerationEvent.ModerationAction action = modEvent.action();

                try {
                    switch (action.action()) {
                        case UnBan:
                            TMISlashCommands.checkAndProcessCommands(this.channel(), "/unban " + item.nick());
                            break;
                        case Delete:
                            TMISlashCommands.checkAndProcessCommands(this.channel(),
                                    "/delete " + item.tags().get("id"));
                            break;
                        case ClearChat:
                            TMISlashCommands.checkAndProcessCommands(this.channel(), "/clear");
                            break;
                        case Timeout:
                            TMISlashCommands.checkAndProcessCommands(this.channel(), "/timeout " + item.nick() + " "
                                    + action.time() + (action.reason() != null ? " " + action.reason() : null));
                            break;
                        case Ban:
                            TMISlashCommands.checkAndProcessCommands(this.channel(),
                                    "/ban " + item.nick() + (action.reason() != null ? " " + action.reason() : null));
                            break;
                        default:
                    }
                } catch (Exception ex) {
                    com.gmt2001.Console.err.printStackTrace(ex);
                }

                if (action.action() != Actions.None && action.warning() != null && !action.warning().isBlank()) {
                    this.session().sayNow(action.warning());
                }
            }
        }).subscribe();
    }
}
