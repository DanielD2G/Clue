package com.clue.tuner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;

import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;

/**
 * CLUE: entry points into the bitrate tuner.
 * The automatic offer appears once per PC + network, the first time a stream is launched.
 */
public final class TunerOffer {
    private TunerOffer() {
    }

    public static Intent intent(Context context, String pcUuid) {
        return new Intent(context, BitrateTunerActivity.class)
                .putExtra(BitrateTunerActivity.EXTRA_PC_UUID, pcUuid);
    }

    /**
     * Called right before a stream is launched. Remembers the PC for the Settings entry and,
     * if this PC + network was never tuned nor skipped (and the network is not wired), asks
     * first. Returns true when it took over; {@code launch} then runs only on "Not now".
     */
    public static boolean maybeOffer(final Activity parent, final ComputerDetails computer, final Runnable launch) {
        if (computer == null || computer.uuid == null) {
            return false;
        }
        final TunerStore store = new TunerStore(parent);
        store.setLastPc(computer.uuid);

        final String network = TunerStore.networkKey(parent);
        if (TunerStore.WIRED.equals(network) || !store.shouldOffer(computer.uuid, network)) {
            return false;
        }

        new AlertDialog.Builder(parent)
                .setTitle(R.string.tuner_offer_title)
                .setMessage(R.string.tuner_offer_msg)
                .setPositiveButton(R.string.tuner_offer_test, (d, w) -> {
                    // Asked once: a later Apply stores the result, anything else leaves it skipped
                    store.markSkipped(computer.uuid, network);
                    parent.startActivity(intent(parent, computer.uuid));
                })
                .setNegativeButton(R.string.tuner_offer_not_now, (d, w) -> {
                    store.markSkipped(computer.uuid, network);
                    launch.run();
                })
                .setOnCancelListener(d -> {
                    // Dismissed without choosing: neither launch nor remember
                })
                .show();
        return true;
    }
}
