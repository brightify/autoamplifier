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

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaRecorder;

import org.brightify.autoamplifier.util.DataSender;
import org.brightify.autoamplifier.util.PreferenceProvider;

import org.androidannotations.annotations.AfterInject;
import org.androidannotations.annotations.Bean;
import org.androidannotations.annotations.EBean;
import org.androidannotations.annotations.RootContext;
import org.androidannotations.annotations.SystemService;

import java.io.IOException;

/**
 * @author <a href="mailto:hyblmatous@gmail.com">Matous Hybl</a>
 */
@EBean
public class Amplifier {
    private static final int MUSIC_STREAM = AudioManager.STREAM_MUSIC;
    private static final int DEFAULT_DELAY_INTERVAL = 100;
    private static final int MIC_ARRAY_LENGTH = 50;
    private static final double VOLUME_CHANGE = 1.2;
    public static final int MIC_DEFAULT_MIN = 2000;
    public static final int MIC_DEFAULT_MAX = 32767;
    public static final int VOLUME_DEFAULT_MIN = 2;
    public static final int VOLUME_DEFAULT_MAX = 15;

    @SystemService
    AudioManager audioManager;

    @RootContext
    Context context;

    @Bean
    DataSender dataSender;

    @Bean
    PreferenceProvider preferenceProvider;

    private MediaRecorder mediaRecorder;

    //amplifier settings variables
    private int volumeLow;
    private int volumeHigh;
    private int micLow;
    private int micHigh;
    private int currentVolume;
    private int lastVolume;
    private int delayInterval = DEFAULT_DELAY_INTERVAL; //100 ms
    private int[] micArray = new int[MIC_ARRAY_LENGTH];
    private boolean UIChangePerformed = false;
    private boolean mediaRecorderInitialised = false;

    public Amplifier() {

    }

    @AfterInject
    void init() {
        currentVolume = audioManager.getStreamVolume(MUSIC_STREAM);
        lastVolume = audioManager.getStreamVolume(MUSIC_STREAM);
        micLow = preferenceProvider.getMicLow();
        micHigh = preferenceProvider.getMicHigh();
        volumeLow = preferenceProvider.getVolumeLow();
        volumeHigh = preferenceProvider.getVolumeHigh();
        if (!mediaRecorderInitialised) {
            initialiseMediaRecorder();
            mediaRecorderInitialised = true;
        }
        initialiseMicArray();
    }

    public void amplify() {
        for (int i = 0; i < MIC_ARRAY_LENGTH; i++) {
            micArray[i] = getAmplitude();
            currentVolume = audioManager.getStreamVolume(MUSIC_STREAM);
            setDelayInterval(i);
            initialiseVolumes();
            setVolume(calculateVolume());
            lastVolume = calculateVolume();
            dataSender.sendToActivity(getAverageMic(), micLow, micHigh, volumeLow, volumeHigh);
            delay();
        }
    }

    private int calculateVolume() {
        int volume = lastVolume;
        double calculatedVolume = (float) volumeLow + ((float) getAverageMic() - (float) micLow) /
                ((float) micHigh - (float) micLow) * ((float) volumeHigh - (float) volumeLow);
        if (Math.abs(calculatedVolume) > VOLUME_CHANGE || UIChangePerformed) {
            volume = (int) Math.round(calculatedVolume);
        }
        UIChangePerformed = false;
        return volume;
    }

    private void initialiseMediaRecorder() {
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
        mediaRecorder.setOutputFile("/dev/null");
        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mediaRecorder.start();
    }

    public void setValues(int volumeLow, int volumeHigh, int micLow, int micHigh) {
        this.volumeLow = volumeLow;
        this.volumeHigh = volumeHigh;
        this.micLow = micLow;
        this.micHigh = micHigh;
    }

    public void setVolumeLow(int volumeLow) {
        this.volumeLow = volumeLow;
    }

    public void setVolumeHigh(int volumeHigh) {
        this.volumeHigh = volumeHigh;
    }

    public void setMicLow(int micLow) {
        this.micLow = micLow;
    }

    public void setMicHigh(int micHigh) {
        this.micHigh = micHigh;
    }

    private int getAverageMic() {
        int average = 0;
        for (int i = 0; i < MIC_ARRAY_LENGTH; i++) {
            average += micArray[i];
        }
        average /= MIC_ARRAY_LENGTH;
        return average;
    }

    private void delay() {
        try {
            Thread.sleep(delayInterval);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void UIChangePerformed() {
        UIChangePerformed = true;
    }

    public void initialiseMicArray() {
        for (int i = 0; i < MIC_ARRAY_LENGTH; i++) {
            micArray[i] = mediaRecorder.getMaxAmplitude();
        }
    }

    private void initialiseVolumes() {
        if (volumeLow < 0) {
            volumeLow = 0;
        }
        if (volumeLow > audioManager.getStreamMaxVolume(MUSIC_STREAM)) {
            volumeLow = audioManager.getStreamMaxVolume(MUSIC_STREAM);
        }
        if (volumeHigh < 0) {
            volumeHigh = 0;
        }
    }

    public void setVolume(int volume) {
        if (volume < volumeLow) {
            volume = volumeLow;
        } else if (volume > volumeHigh) {
            volume = volumeLow;
        }
        lastVolume = volume;
        audioManager.setStreamVolume(MUSIC_STREAM, volume, 0);

    }

    private int getAmplitude() {
        return mediaRecorder.getMaxAmplitude();
    }

    private void setDelayInterval(int iteration) {
        if (iteration != 0 && (micArray[iteration] - micArray[iteration - 1]) < 0) {
            delayInterval = DEFAULT_DELAY_INTERVAL;
        } else {
            delayInterval = DEFAULT_DELAY_INTERVAL / 2;
        }
    }

    private int getMaximalVolume() {
        return audioManager.getStreamMaxVolume(MUSIC_STREAM);
    }

    public void resetValues() {
        micLow = MIC_DEFAULT_MIN;
        micHigh = MIC_DEFAULT_MAX;
        volumeLow = VOLUME_DEFAULT_MIN;
        volumeHigh = getMaximalVolume();
    }

    public void onStop() {
        mediaRecorder.stop();
        mediaRecorderInitialised = false;
    }

    public void increment() {
        volumeLow++;
        volumeHigh++;
        UIChangePerformed();
    }

    public void decrement() {
        if (volumeLow > 0) {
            volumeLow--;
        }
        if (volumeHigh > 0) {
            volumeHigh--;
        }
        UIChangePerformed();
    }

    public void setIntent(Intent intent) {
        String mode = intent.getStringExtra(DataSender.VALUE);
        if (mode != null) {
            if (mode.equals(DataSender.MIC_LOW)) {
                this.setMicLow(intent.getIntExtra(DataSender.MIC_LOW, MIC_DEFAULT_MIN));
            } else if (mode.equals(DataSender.MIC_HIGH)) {
                this.setMicHigh(intent.getIntExtra(DataSender.MIC_HIGH, MIC_DEFAULT_MAX));
            } else if (mode.equals(DataSender.VOLUME_LOW)) {
                this.setVolumeLow(intent.getIntExtra(DataSender.VOLUME_LOW, VOLUME_DEFAULT_MIN));
            } else if (mode.equals(DataSender.VOLUME_HIGH)) {
                this.setVolumeHigh(intent.getIntExtra(DataSender.VOLUME_HIGH, VOLUME_DEFAULT_MAX));
            } else if (mode.equals(DataSender.RESET)) {
                if (intent.getBooleanExtra(DataSender.RESET, false)) {
                    this.resetValues();
                }
            }
            this.UIChangePerformed();
        }
    }

}
