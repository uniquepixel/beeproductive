//Cloned by AI (new entry point for the swarm showcase — the swarm classes it drives are cloned
//from the BeeProductive services package).
package com.example.swarmshowcase;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Self-contained swarm showcase. From a fresh project, all your MainActivity has to do is:
 *
 *   startActivity(new Intent(this, SwarmShowcaseActivity.class));
 *
 * and everything else — honey background, boids swarm, permanent swipe-and-disperse layer,
 * permanent shake-to-scatter detector, and the two bottom sliders (swarm size + angriness) —
 * works with no further wiring. No special permissions are required: the bees are ordinary
 * views inside this activity, not system overlays.
 *
 * Extends the plain android.app.Activity on purpose so the showcase has zero library
 * dependencies (no appcompat/material needed).
 */
public class SwarmShowcaseActivity extends Activity {

    private BeeManager beeManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_swarm_showcase);

        FrameLayout beeContainer = findViewById(R.id.beeContainer);
        TextView swarmSizeLabel = findViewById(R.id.swarmSizeLabel);
        SeekBar swarmSizeSlider = findViewById(R.id.swarmSizeSlider);
        TextView angrinessLabel = findViewById(R.id.angrinessLabel);
        SeekBar angrinessSlider = findViewById(R.id.angrinessSlider);

        beeManager = new BeeManager(this, beeContainer);
        beeManager.setSwarmSize(swarmSizeSlider.getProgress());
        beeManager.setAngriness(angrinessSlider.getProgress() / 100.0);

        swarmSizeSlider.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                beeManager.setSwarmSize(progress);
                swarmSizeLabel.setText("Swarm size: " + progress);
            }
        });

        angrinessSlider.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                beeManager.setAngriness(progress / 100.0);
                angrinessLabel.setText("Angriness: " + progress + "%");
            }
        });

    }

    @Override
    protected void onStart() {
        super.onStart();
        // Posted so the container has been measured/laid out before the swarm reads its size.
        findViewById(R.id.beeContainer).post(() -> beeManager.startSimulation());
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Tear the swarm down while the showcase is off screen; onStart() rebuilds it.
        beeManager.removeAllBees();
    }

    /** Small adapter so the sliders above only override what they need. */
    private abstract static class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
