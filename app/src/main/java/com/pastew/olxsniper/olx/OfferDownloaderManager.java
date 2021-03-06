package com.pastew.olxsniper.olx;


import android.content.Context;
import android.util.Log;

import com.pastew.olxsniper.Globals;
import com.pastew.olxsniper.MyLogger;
import com.pastew.olxsniper.Utils;
import com.pastew.olxsniper.db.Offer;
import com.pastew.olxsniper.db.Search;
import com.pastew.olxsniper.db.SniperDatabaseManager;

import java.util.ArrayList;
import java.util.List;

public class OfferDownloaderManager {

    private static final String TAG = Globals.TAG;
    private static OfferDownloaderManager instance = null;
    private SniperDatabaseManager sniperDatabaseManager;

    List<AbstractDownloader> webDownloaders;

    private OfferDownloaderManager(Context context){
        webDownloaders = new ArrayList<>();
        webDownloaders.add(new OlxDownloader());
        webDownloaders.add(new GumtreeDownloader());

        sniperDatabaseManager = new SniperDatabaseManager(context);

    }

    public static OfferDownloaderManager getInstance(Context context){
        if (instance == null)
            instance = new OfferDownloaderManager(context);

        return instance;
    }

    public List<Offer> downloadNewOffersAndSaveToDatabase() {
        List<Search> searches = sniperDatabaseManager.getAllSearches();
        if (searches.size() == 0) {
            MyLogger.i("No searches. Nothing to do...");
            return new ArrayList<>();
        }

        List<Offer> newOfferList = new ArrayList<>();
        for (Search search : searches) {
            MyLogger.i(String.format("Downloading from: %s", search.getUrl()));

            for (AbstractDownloader webDownloader : webDownloaders) {
                if (webDownloader.canHandleLink(search.getUrl()))
                    newOfferList.addAll(webDownloader.downloadOffersFromWeb(search.getUrl()));
            }

        }
        MyLogger.i(String.format("Downloaded: %d", newOfferList.size()));

        List<Offer> offerList = sniperDatabaseManager.getAllOffers();
        MyLogger.i(String.format("From database: %d", offerList.size()));
        List<Offer> onlyNewOffers = Utils.getOnlyNewOffers(offerList, newOfferList);
        MyLogger.i(String.format("Only new: %d", onlyNewOffers.size()));

        if (onlyNewOffers.size() > 0) {
            MyLogger.i("Only new > 0");
            sniperDatabaseManager.insertOffers(onlyNewOffers);
            MyLogger.i("Only new > 0 -> Afer inserting to DB");
        } else {
            MyLogger.i("Checked Web for new offers, but nothing new found");
        }

        MyLogger.i("sniperDatabase.close()");

        //sniperDatabase.close();

        return onlyNewOffers;
    }
}
