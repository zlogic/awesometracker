/*
 * Awesome Time Tracker project.
 * Licensed under Apache 2.0 License: http://www.apache.org/licenses/LICENSE-2.0
 * Author: Dmitry Zolotukhin <zlogic@gmail.com>
 */
package org.zlogic.att.ui;

import java.time.Instant;

/**
 * Class for detecting rapid firing of missed timer events. Prevents accumulated
 * timer events from causing sluggish performance - for example when waking up
 * from sleep or hibernation.
 *
 * @author Dmitry Zolotukhin <a
 * href="mailto:zlogic@gmail.com">zlogic@gmail.com</a>
 */
public class TimerRapidFiringDetector {

	/**
	 * The default threshold
	 */
	private static long defaultThreshold = 1000;
	/**
	 * The time in milliseconds between consecutive events which should be
	 * considered "rapid" firing
	 */
	private long threshold;

	/**
	 * Last time the timer was fired
	 */
	private Instant lastFired;

	/**
	 * Creates a TimerMissedEventConsumer with the default threshold
	 */
	public TimerRapidFiringDetector() {
		this(defaultThreshold);
	}

	/**
	 * Creates a TimerMissedEventConsumer
	 *
	 * @param threshold the time in milliseconds between consecutive events
	 * which should be considered "rapid" firing
	 */
	public TimerRapidFiringDetector(long threshold) {
		this.threshold = threshold;
	}

	/**
	 * Returns the time in milliseconds between consecutive events which should
	 * be considered "rapid" firing
	 *
	 * @return the time in milliseconds between consecutive events which should
	 * be considered "rapid" firing
	 */
	public long getThreshold() {
		return threshold;
	}

	/**
	 * Tracks the current timer firing and returns true if timer is firing
	 * rapidly
	 *
	 * @return true if timer is firing rapidly
	 */
	public boolean isRapidFiring() {
		Instant currentFired = Instant.now();
		if (lastFired == null) {
			lastFired = currentFired;
			return false;
		}
		boolean isRapid = lastFired.plusMillis(threshold).isAfter(currentFired);
		lastFired = currentFired;
		return isRapid;
	}
}
