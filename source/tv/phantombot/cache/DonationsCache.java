/* astyle --style=java --indent=spaces=4 --mode=java */

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
package tv.phantombot.cache;

import java.net.URISyntaxException;
import java.util.Collections;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import com.gmt2001.util.concurrent.ExecutorService;
import com.illusionaryone.StreamLabsAPI;

import net.engio.mbassy.listener.Handler;
import tv.phantombot.PhantomBot;
import tv.phantombot.event.EventBus;
import tv.phantombot.event.Listener;
import tv.phantombot.event.streamlabs.donate.StreamLabsDonationEvent;
import tv.phantombot.event.streamlabs.donate.StreamLabsDonationInitializedEvent;
import tv.phantombot.event.webpanel.websocket.WebPanelSocketUpdateEvent;

public class DonationsCache implements Listener {

    private static final DonationsCache INSTANCE = new DonationsCache();

    public static DonationsCache instance() {
        return INSTANCE;
    }

    private ScheduledFuture<?> updateFuture = null;
    private boolean firstUpdate = true;

    private DonationsCache() {
        this.updateFuture = ExecutorService.scheduleAtFixedRate(this::run, 20, 30, TimeUnit.SECONDS);
        ExecutorService.execute(() -> EventBus.instance().register(this));
    }

    private void run() {
        try {
            if (StreamLabsAPI.hasAccessToken()) {
                this.updateCache();
            }
        } catch (Exception ex) {
            com.gmt2001.Console.err.printStackTrace(ex);
        }
    }

    private void updateCache() throws Exception {
        JSONObject jsonResult;
        JSONArray donations = null;
        int lastId = PhantomBot.instance().getDataStore().GetInteger("settings", "", "DonationsCache_lastId");

        com.gmt2001.Console.debug.println("DonationsCache::updateCache");

        jsonResult = StreamLabsAPI.instance().GetDonations(lastId);

        if (jsonResult.getBoolean("_success")) {
            if (jsonResult.getInt("_http") == 200) {
                donations = jsonResult.getJSONArray("data");
            } else if (jsonResult.optString("message", "").contains("Unauthorized")) {
                com.gmt2001.Console.err.println("DonationsCache.updateCache: Bad API key for StreamLabs.");
                return;
            }
        }

        if (donations != null) {
            for (int i = 0; i < donations.length(); i++) {
                int donationId = Integer.parseInt(donations.getJSONObject(i).get("donation_id").toString());
                if (donationId > lastId) {
                    lastId = donationId;
                    if (!PhantomBot.instance().getDataStore().exists("donations", donations.getJSONObject(i).get("donation_id").toString())) {
                        EventBus.instance().postAsync(new StreamLabsDonationEvent(donations.getJSONObject(i)));
                    }
                }
            }
        }

        if (this.firstUpdate) {
            this.firstUpdate = false;
            EventBus.instance().postAsync(new StreamLabsDonationInitializedEvent());
        }

        PhantomBot.instance().getDataStore().SetInteger("settings", "", "DonationsCache_lastId", lastId);
    }

    @Handler
    public void onWebPanelSocketUpdateEvent(WebPanelSocketUpdateEvent event) {
        if (event.getId().equals("streamlabs_authorize") && event.getArgs().length >= 4) {
            try {
                PhantomBot.instance().panelhandler().sendObject(event.getId(), Collections.singletonMap("success",
                StreamLabsAPI.authorize(event.getArgs()[0], event.getArgs()[1], event.getArgs()[2], event.getArgs()[3])));
            } catch (URISyntaxException ex) {
                PhantomBot.instance().panelhandler().sendObject(event.getId(), Collections.singletonMap("success", false));
                com.gmt2001.Console.err.printStackTrace(ex);
            }
        }
    }

    public void kill() {
        if (this.updateFuture != null) {
            this.updateFuture.cancel(true);
        }
    }
}
