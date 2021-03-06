/*
    AutoAmplifier - Android application that changes media volume according to noise in the surroundings.
    Copyright (C) 2014  Brightify s.r.o.

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.brightify.autoamplifier;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;

import org.androidannotations.annotations.AfterViews;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.CheckedChange;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.OptionsItem;
import org.androidannotations.annotations.OptionsMenu;
import org.androidannotations.annotations.SeekBarProgressChange;
import org.androidannotations.annotations.SystemService;
import org.androidannotations.annotations.UiThread;
import org.androidannotations.annotations.ViewById;
import org.brightify.autoamplifier.settings.SettingsActivity;
import org.brightify.autoamplifier.util.DataSender;
import org.brightify.autoamplifier.util.PreferenceProvider;

@OptionsMenu(R.menu.main)
@EActivity(R.layout.main_activity)
public class MainActivity extends Activity {

    private static final int VOLUME_THREAD_DELAY = 200;

    @ViewById(R.id.enable_switch)
    Switch enable;

    @ViewById(R.id.current_mic_progress)
    ProgressBar currentMic;

    @ViewById(R.id.current_volume_progress)
    ProgressBar currentVolume;

    @ViewById(R.id.quiet_mic_seekBar)
    SeekBar quietMic;

    @ViewById(R.id.quiet_volume_seekBar)
    SeekBar quietVolume;

    @ViewById(R.id.noisy_mic_seekBar)
    SeekBar noisyMic;

    @ViewById(R.id.noisy_volume_seekBar)
    SeekBar noisyVolume;

    @ViewById
    LinearLayout currentMicGroup;

    @SystemService
    AudioManager audioManager;

    @SystemService
    ActivityManager activityManager;

    @Bean
    DataSender dataSender;

    @Bean
    PreferenceProvider preferenceProvider;

    private boolean volumeThreadRunning = true;

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(AmplifierService.ACTION_DISABLE)) {
                enable.setChecked(false);
                currentMicGroup.setVisibility(View.INVISIBLE);
            } else {
                currentMic.setProgress(intent.getIntExtra(DataSender.MIC, 0));
            }
        }
    };

    @AfterViews
    void initialiseViews() {
        currentVolume.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        quietVolume.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        quietVolume.setProgress(preferenceProvider.getVolumeLow());
        noisyVolume.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        noisyVolume.setProgress(preferenceProvider.getVolumeHigh());
        quietMic.setProgress(preferenceProvider.getMicLow());
        noisyMic.setProgress(preferenceProvider.getMicHigh());
        enable.setChecked(true);
    }

    @UiThread
    void updateCurrentVolume() {
        currentVolume.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
    }

    @OptionsItem(R.id.action_settings)
    void openSettings() {
        if (getApplicationContext() != null) {
            startActivity(new Intent(getApplicationContext(), SettingsActivity.class));
        }
    }

    @OptionsItem(R.id.action_reset)
    void reset() {
        dataSender.sendReset();
        preferenceProvider.resetPreferences();
        quietMic.setProgress(Amplifier.MIC_DEFAULT_MIN);
        noisyMic.setProgress(Amplifier.MIC_DEFAULT_MAX);
        quietVolume.setProgress(Amplifier.VOLUME_DEFAULT_MIN);
        noisyVolume.setProgress(Amplifier.VOLUME_DEFAULT_MAX);
    }

    @CheckedChange(R.id.enable_switch)
    void serviceEnabled(CompoundButton button) {
        if (button.isChecked()) {
            if (!isServiceRunning()) {
                AmplifierService_.intent(this).start();
            }
            if(!preferenceProvider.isSavingEnabled()){
                reset();
            }
            currentMicGroup.setVisibility(View.VISIBLE);
        } else {
            AmplifierService_.intent(this).stop();
            currentMicGroup.setVisibility(View.INVISIBLE);
        }

    }

    @SeekBarProgressChange(R.id.quiet_mic_seekBar)
    void quietMicChange(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            dataSender.sendLowMic(progress);
            saveValues();
        }
    }

    @SeekBarProgressChange(R.id.quiet_volume_seekBar)
    void quietVolumeChange(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            dataSender.sendLowVolume(progress);
            dataSender.sendHighVolume(noisyVolume.getProgress());
            saveValues();
        }
    }

    @SeekBarProgressChange(R.id.noisy_mic_seekBar)
    void noisyMicChange(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            dataSender.sendHighMic(progress);
            saveValues();
        }
    }

    @SeekBarProgressChange(R.id.noisy_volume_seekBar)
    void noisyVolumeChange(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            dataSender.sendHighVolume(progress);
            dataSender.sendLowVolume(quietVolume.getProgress());
            saveValues();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (getApplicationContext() != null) {
            IntentFilter intentFilter = new IntentFilter(AmplifierService.ACTION_DISABLE);
            intentFilter.addAction(DataSender.INTENT_TO_ACTIVITY);
            getApplicationContext().registerReceiver(broadcastReceiver, intentFilter);
        } else {
            throw new RuntimeException("Unable to register activity receiver");
        }
        volumeThreadRunning = true;
        Thread volumeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (volumeThreadRunning) {
                    updateCurrentVolume();
                    try {
                        Thread.sleep(VOLUME_THREAD_DELAY);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        volumeThread.start();
    }

    @Override
    public void onStop() {
        super.onStop();
        volumeThreadRunning = false;
        if (getApplicationContext() != null) {
            getApplicationContext().unregisterReceiver(broadcastReceiver);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!isServiceRunning()) {
            enable.setChecked(false);
            currentMicGroup.setVisibility(View.INVISIBLE);
        }
    }

    private boolean isServiceRunning() {
        if (activityManager != null && activityManager.getRunningServices(Integer.MAX_VALUE) != null) {
            for (ActivityManager.RunningServiceInfo serviceInfo :
                    activityManager.getRunningServices(Integer.MAX_VALUE)) {
                if (AmplifierService_.class.getName().equals(serviceInfo.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void saveValues() {
        preferenceProvider.saveValues(quietVolume.getProgress(), noisyVolume.getProgress(),
                quietMic.getProgress(), noisyMic.getProgress());
    }
}
