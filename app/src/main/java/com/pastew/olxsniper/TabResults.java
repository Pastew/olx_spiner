package com.pastew.olxsniper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StrictMode;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.Job;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.Trigger;
import com.pastew.olxsniper.db.Offer;
import com.pastew.olxsniper.db.Search;
import com.pastew.olxsniper.db.SniperDatabaseManager;
import com.pastew.olxsniper.olx.OfferDownloaderManager;

import java.util.ArrayList;
import java.util.List;

public class TabResults extends Fragment implements RecyclerItemTouchHelper.RecyclerItemTouchHelperListener{

    public static final String OLX_URL = "https://www.olx.pl/elektronika/telefony-komorkowe/q-iphone";
    public static final String OLX_URL_IPHONE = "https://www.olx.pl/oferty/q-iphone-5s/?search%5Bfilter_float_price%3Afrom%5D=400&search%5Bfilter_float_price%3Ato%5D=500";
    private int updaterDelayInSeconds = 60;

    private OffersAdapter offersAdapter;
    private List<Offer> offerList;

    private SniperDatabaseManager sniperDatabaseManager;

    private IntentFilter filter = new IntentFilter(Globals.DATABASE_UPDATE_BROADCAST);
    private DatabaseUpdateBroadcastReceiver databaseUpdateBroadcastReceiver;

    private Context context;
    private View view;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.tab_results, container, false);
        context = getContext();

        setupStrictMode();
        setupFab();
        setupRecyclerView();
        setupButtons();
        setupService();
        setupOfferDbManager();
        new SetSampleSearches().execute();
        return view;
    }

    private void setupOfferDbManager() {
        this.sniperDatabaseManager = new SniperDatabaseManager(context);
    }

    @Override
    public void onResume() {
        registerReceiver();
        offersAdapter.notifyDataSetChanged();
        new DownloadOffersFromDatabaseTask().execute();
        super.onResume();
    }

    @Override
    public void onPause() {
        new SharedPrefsManager(context).setLastTimeUserSawOffersToNow();
        unregisterReceiver();
        super.onPause();
    }

    private void registerReceiver() {
        databaseUpdateBroadcastReceiver = new DatabaseUpdateBroadcastReceiver();
        getActivity().registerReceiver(databaseUpdateBroadcastReceiver, filter);
    }

    private void unregisterReceiver() {
        try {
            getActivity().unregisterReceiver(databaseUpdateBroadcastReceiver);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Receiver not registered")) {
                // Ignore this exception. This is exactly what is desired
                Log.w(Globals.TAG, "Tried to unregister the reciver when it's not registered");
            } else {
                // unexpected, re-throw
                throw e;
            }
        }
    }



    private void setupService() {
        FirebaseJobDispatcher dispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(context));
        dispatcher.cancelAll();
        Job myJob = dispatcher.newJobBuilder()
                .setService(UpdaterJobService.class)
                .setTag(UpdaterJobService.class.getSimpleName())
                .setRecurring(true)
                .setTrigger(Trigger.executionWindow(updaterDelayInSeconds, updaterDelayInSeconds + 1))
                .setLifetime(Lifetime.FOREVER)
                .setReplaceCurrent(true)
                .build();

        dispatcher.mustSchedule(myJob);
    }

    private void setupStrictMode() {
        //TODO: Find out why it is needed
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
    }

    private void setupFab() {
        FloatingActionButton fab = view.findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // TODO: here should open window with added new OLX Listner
                MyLogger.getInstance().showLogsInDebugWindow(context);
            }
        });
    }

    private void setupRecyclerView() {
        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(context);
        recyclerView.setLayoutManager(layoutManager);
        offerList = new ArrayList<>();
        offersAdapter = new OffersAdapter(context, offerList);
        recyclerView.setAdapter(offersAdapter);

        ItemTouchHelper.SimpleCallback itemTouchHelperCallback =
                new RecyclerItemTouchHelper(0,
                        ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                        this);

        new ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(recyclerView);
    }

    private void setupButtons() {
        view.findViewById(R.id.clearDatabaseButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new DeleteAllOffersFromDatabase().execute();
                offerList.clear();
                offersAdapter.notifyDataSetChanged();
            }
        });

        view.findViewById(R.id.refreshDatabaseButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ((Button) v).setText("Sprawdzam...");
                ((Button) v).setEnabled(false);
                new DownloadNewOffersFromOlxTask().execute();
            }
        });

        view.findViewById(R.id.removeAllOffersButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new SetRemovedFlagTaskTrueForOffers().execute(offerList);
            }
        });
    }

    private void notifyUserAboutNewOffers(List<Offer> onlyNewOffers) {
        int offersNotSeenByUser = 0;
        for (Offer offer : onlyNewOffers)
            if (!Utils.checkIfOfferWasSeenByUser(context, offer))
                ++offersNotSeenByUser;

        if (offersNotSeenByUser == 0)
            return;

        Snackbar snackbar = Snackbar
                .make(getActivity().findViewById(R.id.main_content), String.format("%d nowe oferty!", onlyNewOffers.size()), Snackbar.LENGTH_LONG)
                .setAction("Nie klikaj!", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        Toast.makeText(context, "Miałeś nie klikać!", Toast.LENGTH_SHORT).show();
                    }
                });
        snackbar.show();
    }

    @Override
    public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction, int position) {
        if (viewHolder instanceof OffersAdapter.ViewHolder) {
            // get the removed item name to display it in snack bar
            String name = offerList.get(viewHolder.getAdapterPosition()).title;

            // backup of removed item for undo purpose
            final Offer deletedItem = offerList.get(viewHolder.getAdapterPosition());
            final int deletedIndex = viewHolder.getAdapterPosition();

            // remove the item from recycler view
            offersAdapter.removeItem(viewHolder.getAdapterPosition());

            // set in database "removed" flag
            new SetRemovedFlagTaskTrue().execute(deletedItem);

            // showing snack bar with Undo option
            Snackbar snackbar = Snackbar
                    .make(view.findViewById(R.id.constrainLayout), "Usunięto " + name, Snackbar.LENGTH_LONG);
            snackbar.setAction("COFNIJ", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    // undo is selected, restore the deleted item
                    offersAdapter.restoreItem(deletedItem, deletedIndex);
                    new SetRemovedFlagTaskFalse().execute(deletedItem);
                }
            });

            snackbar.setActionTextColor(Color.YELLOW);
            snackbar.show();
        }
    }

    private class DownloadOffersFromDatabaseTask extends AsyncTask<Void, Integer, List<Offer>> {
        protected List<Offer> doInBackground(Void... params) {
            List<Offer> newOfferList = sniperDatabaseManager.getAllNotRemovedOffersByDate();
            List<Offer> onlyNewOffers = Utils.getOnlyNewOffers(offerList, newOfferList);
            return onlyNewOffers;
        }

        protected void onPostExecute(List<Offer> onlyNewOffers) {
            if (onlyNewOffers.size() > 0) {
                offerList.addAll(0, onlyNewOffers);
                offersAdapter.notifyDataSetChanged();
                //offersAdapter.notifyItemRangeInserted(0, onlyNewOffers.size()); // TODO: check if it works

                notifyUserAboutNewOffers(onlyNewOffers);
            } else {
                Snackbar snackbar = Snackbar
                        .make(getActivity().findViewById(R.id.main_content), String.format("Nie ma nowych ofert."), Snackbar.LENGTH_LONG)
                        .setAction("Nie klikaj!", new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                Toast.makeText(context, "Miałeś nie klikać!", Toast.LENGTH_SHORT).show();
                            }
                        });
                snackbar.show();
            }
        }
    }

    private class SetSampleSearches extends AsyncTask<Void, Integer, Void> {
        protected Void doInBackground(Void... params) {
            Search search1 = new Search("pilne", 0, 500, 1, "Kraków");
            Search search2 = new Search("xbox pad", 0, 120, 2, "Kraków");
            Search search3 = new Search("iphone 5", 350, 500, 3, "Kraków");

            sniperDatabaseManager.addSearch(search1);
            sniperDatabaseManager.addSearch(search2);
            sniperDatabaseManager.addSearch(search3);
            return null;
        }
    }

    private class DownloadNewOffersFromOlxTask extends AsyncTask<String, Integer, Void> {
        protected Void doInBackground(String... urls) {
            OfferDownloaderManager.getInstance(context).downloadNewOffersAndSaveToDatabase();
            return null;
        }

        protected void onPostExecute(Void param) {
            new DownloadOffersFromDatabaseTask().execute();
            ((Button) view.findViewById(R.id.refreshDatabaseButton)).setText("Odśwież");
            ((Button) view.findViewById(R.id.refreshDatabaseButton)).setEnabled(true);
        }
    }

    private class DeleteAllOffersFromDatabase extends AsyncTask<Void, Void, Integer> {
        protected Integer doInBackground(Void... voids) {
            sniperDatabaseManager.deleteAllOffers();
            return null;
        }
    }

    private class SetRemovedFlagTaskTrueForOffers extends AsyncTask<List<Offer>, Void, Void> {
        protected Void doInBackground(List<Offer>... offers) {
            sniperDatabaseManager.setRemovedFlag(offers[0], true);
            return null;
        }

        protected void onPostExecute(Void param) {
            int size = offerList.size();
            if (size > 0) {
                for (int i = 0; i < size; i++) {
                    offerList.remove(0);
                }

                offersAdapter.notifyItemRangeRemoved(0, size);
            }
        }
    }

    private class SetRemovedFlagTaskTrue extends AsyncTask<Offer, Void, Void> {
        protected Void doInBackground(Offer... offers) {
            sniperDatabaseManager.setRemovedFlag(offers[0], true);
            return null;
        }
    }

    private class SetRemovedFlagTaskFalse extends AsyncTask<Offer, Void, Void> {
        protected Void doInBackground(Offer... offers) {
            sniperDatabaseManager.setRemovedFlag(offers[0], false);
            return null;
        }
    }

    private class DatabaseUpdateBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            new DownloadOffersFromDatabaseTask().execute();
            Log.i(Globals.TAG, "broadcast received");
        }
    }
}