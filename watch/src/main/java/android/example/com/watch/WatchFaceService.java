/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.example.com.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.example.android.sunshine.app.R;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WatchFaceService extends CanvasWatchFaceService {

    public String LOG_TAG =  "Watch_face";

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<WatchFaceService.Engine> mWeakReference;

        public EngineHandler(WatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            WatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {


        private static final String WEATHER_PATH = "/weather";
        private static final String HIGH_TEMPERATURE = "high_temperature";
        private static final String LOW_TEMPERATURE = "low_tempersture";
        private static final String WEATHER_ID = "weather_id";

        private GoogleApiClient mGoogleApiClient;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextPaint;
        boolean mAmbient;
        Time mTime;

        private static final int SPACE_BETWEEN_TEMPERATURES = 10;

      /*  final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };*/
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                calendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        int mTapCount;



        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        String highTemperature;
        String lowTemperature;
        Bitmap weatherIcon;

        int digitalTextColor = -1;
        int digitalTextLightColor = -1;

        Paint linePaint;
        Paint timeTextPaint;
        Paint dateTextPaint;
        Paint highTemperatureTextPaint;
        Paint lowTemperatureTextPaint;

        float mYOffset;
        float timeYOffset;
        float dateYOffset;
        float weatherYOffset;

        Calendar calendar;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(WatchFaceService.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            setWatchFaceStyle(new WatchFaceStyle.Builder(WatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = WatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

            timeYOffset = resources.getDimension(R.dimen.time_y_offset);
            dateYOffset = resources.getDimension(R.dimen.date_y_offset);
            weatherYOffset = resources.getDimension(R.dimen.weather_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaint = new Paint();
            mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));

            digitalTextColor = ContextCompat.getColor(WatchFaceService.this, R.color.digital_text);
            digitalTextLightColor = ContextCompat.getColor(WatchFaceService.this, R.color.digital_text_light);

            linePaint = new Paint();
            linePaint.setColor(digitalTextLightColor);

            timeTextPaint = createTextPaint(digitalTextColor);
            dateTextPaint = createTextPaint(digitalTextLightColor);
            highTemperatureTextPaint = createTextPaint(digitalTextColor);
            lowTemperatureTextPaint = createTextPaint(digitalTextLightColor);

            // allocate a Calendar to calculate local time using the UTC time and time zone
            calendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
               // mTime.clear(TimeZone.getDefault().getID());
               // mTime.setToNow();

                calendar.setTimeZone(TimeZone.getDefault());

            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                    ;
                }
                unregisterReceiver();
            }


            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WatchFaceService.this.getResources();

            float timeTextSize = resources.getDimension(R.dimen.time_text_size);
            timeTextPaint.setTextSize(timeTextSize);

            float dateTextSize = resources.getDimension(R.dimen.date_text_size);
            dateTextPaint.setTextSize(dateTextSize);

            float temperatureTextSize = resources.getDimension(R.dimen.temperature_text_size);
            highTemperatureTextPaint.setTextSize(temperatureTextSize);
            lowTemperatureTextPaint.setTextSize(temperatureTextSize);

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                linePaint.setColor(inAmbientMode ? digitalTextColor : digitalTextLightColor);
                dateTextPaint.setColor(inAmbientMode ? digitalTextColor : digitalTextLightColor);
                lowTemperatureTextPaint.setColor(inAmbientMode ? digitalTextColor : digitalTextLightColor);
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    timeTextPaint.setAntiAlias(!inAmbientMode);
                    dateTextPaint.setAntiAlias(!inAmbientMode);
                    highTemperatureTextPaint.setAntiAlias(!inAmbientMode);
                    lowTemperatureTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = WatchFaceService.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }


            calendar.setTimeInMillis(System.currentTimeMillis());
            int seconds = calendar.get(Calendar.SECOND);
            int minutes = calendar.get(Calendar.MINUTE);
            int hours = calendar.get(Calendar.HOUR);
            int am_pm=calendar.get(Calendar.AM_PM);
            String amOrpm=((am_pm==Calendar.AM)?"AM":"PM");
            String time = mAmbient
                    ? String.format("%d:%02d", hours, minutes)
                    : String.format("%d:%02d:%02d %s", hours, minutes, seconds ,amOrpm);

            // Draw time text in x-center of screen
            float timeTextWidth = timeTextPaint.measureText(time);
            float halfTimeTextWidth = timeTextWidth / 2;
            float xOffsetTime = bounds.centerX() - halfTimeTextWidth;
            canvas.drawText(time, xOffsetTime, timeYOffset, timeTextPaint);

            SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM dd yyyy");
            String date = dateFormat.format(calendar.getTime()).toUpperCase();

            float dateTextWidth = dateTextPaint.measureText(date);
            float halfDateTextWidth = dateTextWidth / 2;
            float xOffsetDate = bounds.centerX() - halfDateTextWidth;
            canvas.drawText(date, xOffsetDate, dateYOffset, dateTextPaint);

            // Draw high and low temperature, icon for weather condition
            if (weatherIcon != null && highTemperature != null && lowTemperature != null) {
                float highTemperatureTextWidth = highTemperatureTextPaint.measureText(highTemperature);
                float lowTemperatureTextWidth = lowTemperatureTextPaint.measureText(lowTemperature);

                Rect temperatureBounds = new Rect();
                highTemperatureTextPaint.getTextBounds(highTemperature, 0, highTemperature.length(), temperatureBounds);

                float lineYOffset = (dateYOffset + weatherYOffset) / 2 - (temperatureBounds.height() / 2);
                canvas.drawLine(bounds.centerX() - 4 * SPACE_BETWEEN_TEMPERATURES, lineYOffset,
                        bounds.centerX() + 4 * SPACE_BETWEEN_TEMPERATURES, lineYOffset, linePaint);

                float xOffsetHighTemperature;
                if (mAmbient) {
                    xOffsetHighTemperature = bounds.centerX() - ((highTemperatureTextWidth + lowTemperatureTextWidth + SPACE_BETWEEN_TEMPERATURES) / 2);
                } else {
                    xOffsetHighTemperature = bounds.centerX() - (highTemperatureTextWidth / 2);

                    canvas.drawBitmap(weatherIcon, xOffsetHighTemperature - weatherIcon.getWidth() - 2 * SPACE_BETWEEN_TEMPERATURES,
                            weatherYOffset - (temperatureBounds.height() / 2) - (weatherIcon.getHeight() / 2), null);
                }

                float xOffsetLowTemperature = xOffsetHighTemperature + highTemperatureTextWidth + SPACE_BETWEEN_TEMPERATURES;

                canvas.drawText(highTemperature, xOffsetHighTemperature, weatherYOffset, highTemperatureTextPaint);
                canvas.drawText(lowTemperature, xOffsetLowTemperature, weatherYOffset, lowTemperatureTextPaint);

            }

        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle bundle) {

            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Log.d(LOG_TAG, "onConnected");

        }

        @Override
        public void onConnectionSuspended(int i) {

            Log.d(LOG_TAG, "onConnectionSuspended");

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

            Log.d(LOG_TAG, "onConnectionFailed");

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvent) {
            for (DataEvent event : dataEvent) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = event.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(WEATHER_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        setWeatherData(dataMap.getString(HIGH_TEMPERATURE),dataMap.getString(LOW_TEMPERATURE),dataMap.getInt(WEATHER_ID));
                        invalidate();

                    }
                }
            }
        }
        private void setWeatherData(String high, String low , int weather_id ) {

            highTemperature = high;
            lowTemperature = low;
            weatherIcon = BitmapFactory.decodeResource(WatchFaceService.this.getResources(), WatchFaceUtility.getIconResourceForWeatherCondition(weather_id));

        }

        
    }
}
