/*
 * Awesome Time Tracker project.
 * Licensed under Apache 2.0 License: http://www.apache.org/licenses/LICENSE-2.0
 * Author: Dmitry Zolotukhin <zlogic@gmail.com>
 */
package org.zlogic.att.ui;

//FIXME: catch all Java FX exceptions here once this is supported by Java 8
/**
 * UI interface for displaying an exception
 *
 * @author Dmitry Zolotukhin <zlogic@gmail.com>
 */
public interface ExceptionHandler {

	/**
	 * Shows an exception dialog
	 *
	 * @param explanation the exception human-readable explanation
	 * @param ex the actual exception
	 */
	public void showException(String explanation, Throwable ex);
}
