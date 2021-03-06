/*
 * Awesome Time Tracker project.
 * Licensed under Apache 2.0 License: http://www.apache.org/licenses/LICENSE-2.0
 * Author: Dmitry Zolotukhin <zlogic@gmail.com>
 */
package org.zlogic.att.ui.report;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import net.sf.dynamicreports.jasper.builder.JasperReportBuilder;
import net.sf.dynamicreports.jasper.builder.export.Exporters;
import net.sf.dynamicreports.jasper.builder.export.JasperHtmlExporterBuilder;
import net.sf.dynamicreports.jasper.builder.export.JasperPdfExporterBuilder;
import net.sf.dynamicreports.report.base.expression.AbstractSimpleExpression;
import net.sf.dynamicreports.report.base.expression.AbstractValueFormatter;
import net.sf.dynamicreports.report.builder.DynamicReports;
import net.sf.dynamicreports.report.builder.component.ComponentBuilder;
import net.sf.dynamicreports.report.builder.component.VerticalListBuilder;
import net.sf.dynamicreports.report.builder.group.CustomGroupBuilder;
import net.sf.dynamicreports.report.builder.style.StyleBuilder;
import net.sf.dynamicreports.report.builder.tableofcontents.TableOfContentsCustomizer;
import net.sf.dynamicreports.report.constant.HorizontalAlignment;
import net.sf.dynamicreports.report.constant.Markup;
import net.sf.dynamicreports.report.constant.Orientation;
import net.sf.dynamicreports.report.constant.PageOrientation;
import net.sf.dynamicreports.report.constant.PageType;
import net.sf.dynamicreports.report.constant.VerticalAlignment;
import net.sf.dynamicreports.report.definition.ReportParameters;
import net.sf.dynamicreports.report.definition.expression.DRIExpression;
import net.sf.dynamicreports.report.exception.DRException;
import net.sf.jasperreports.engine.JREmptyDataSource;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;
import org.zlogic.att.data.CustomField;
import org.zlogic.att.data.Task;
import org.zlogic.att.data.TimeSegment;
import org.zlogic.att.data.reporting.DateTools;
import org.zlogic.att.data.reporting.ReportQuery;
import org.zlogic.att.ui.ExceptionLogger;
import org.zlogic.att.ui.adapters.CustomFieldAdapter;
import org.zlogic.att.ui.adapters.DataManager;
import org.zlogic.att.ui.adapters.DurationFormatter;

/*
 * TODO: move internal classes into other files
 */
/**
 * Class for generating a report
 *
 * @author Dmitry Zolotukhin <a
 * href="mailto:zlogic@gmail.com">zlogic@gmail.com</a>
 */
public class Report {

	/**
	 * Localization messages
	 */
	private static final ResourceBundle messages = ResourceBundle.getBundle("org/zlogic/att/ui/report/messages");
	/**
	 * DataManager reference
	 */
	private DataManager dataManager;
	/**
	 * Report start date
	 */
	private LocalDate startDate;
	/**
	 * Report end date
	 */
	private LocalDate endDate;
	/**
	 * Property to indicate the progress (0..1)
	 */
	private DoubleProperty progress = new SimpleDoubleProperty(-1);
	/**
	 * Generated report HTML
	 */
	private String reportHTML;
	/**
	 * Generated report
	 */
	private JasperReportBuilder report;
	/**
	 * Path for storing images from HTML files
	 */
	private Path htmlImagesPath;
	/**
	 * Formatter to output date and time
	 */
	private AbstractValueFormatter<String, Date> dateTimeFormatter = new AbstractValueFormatter<String, Date>() {
		@Override
		public String format(Date value, ReportParameters reportParameters) {
			return MessageFormat.format(messages.getString("DATE_TIME_FORMAT"), new Object[]{value});
		}
	};
	/**
	 * Formatter to output a Joda period
	 */
	private AbstractValueFormatter<String, Duration> periodFormatter = new AbstractValueFormatter<String, Duration>() {
		@Override
		public String format(Duration value, ReportParameters reportParameters) {
			return DurationFormatter.formatDuration(value);
		}
	};
	/**
	 * Formatter to output a custom field name
	 */
	private AbstractValueFormatter<String, CustomField> customFieldNameFormatter = new AbstractValueFormatter<String, CustomField>() {
		@Override
		public String format(CustomField value, ReportParameters reportParameters) {
			return value.getName();
		}
	};
	/**
	 * Formatter to output a custom field value for the task
	 */
	private AbstractValueFormatter<String, CustomField> customFieldValueFormatter = new AbstractValueFormatter<String, CustomField>() {
		@Override
		public String format(CustomField value, ReportParameters reportParameters) {
			Task task = reportParameters.getFieldValue("task"); //NOI18N
			return task.getCustomField(value);
		}
	};
	/**
	 * Expression which prints on the third or later pages - resolves a bug (?)
	 * where a subreport starts from page 2
	 */
	private AbstractSimpleExpression<Boolean> subreportPrintNotInFirstPageExpression = new AbstractSimpleExpression<Boolean>() {
		@Override
		public Boolean evaluate(ReportParameters reportParameters) {
			return reportParameters.getPageNumber() > 2;
		}
	};

	/**
	 * Class to store a date and a time segment
	 */
	public class DateTimeSegment {

		/**
		 * The day start date
		 */
		private Date dayStart;
		/**
		 * The day end date
		 */
		private Date dayEnd;
		/**
		 * The times segment
		 */
		private TimeSegment timeSegment;

		/**
		 * Constructor of a DateTimeSegment
		 *
		 * @param dayStart the starting date day
		 * @param dayEnd the ending date day
		 * @param timeSegment the TimeSegment
		 */
		private DateTimeSegment(Date dayStart, Date dayEnd, TimeSegment timeSegment) {
			this.dayStart = dayStart;
			this.dayEnd = dayEnd;
			this.timeSegment = timeSegment;
		}

		/**
		 * Returns the date
		 *
		 * @return the date
		 */
		public Date getDate() {
			return dayStart;
		}

		/**
		 * Returns the time segment
		 *
		 * @return the time segment
		 */
		public TimeSegment getTimeSegment() {
			return timeSegment;
		}

		/**
		 * Returns the duration of the time segment, clipped with the report's
		 * Start and End dates, in hours
		 *
		 * @return the duration of the time segment
		 */
		public double getDurationHours() {
			Duration period = timeSegment.getClippedDuration(dayStart, dayEnd);
			return ((double) period.getSeconds()) / 3600;
		}

		/**
		 * Returns the duration of the time segment, clipped with the report's
		 * Start and End dates as a formatted HH:mm:ss string
		 *
		 * @return the duration of the time segment
		 */
		public String getDuration() {
			Duration period = timeSegment.getClippedDuration(DateTools.getInstance().convertDateToStartOfDay(startDate), DateTools.getInstance().convertDateToEndOfDay(endDate));
			return DurationFormatter.formatDuration(period);
		}
	}

	/**
	 * Class to store a custom field value and a duration for this custom field
	 */
	public class CustomFieldTime {

		/**
		 * The CustomField
		 */
		private CustomField customField;
		/**
		 * The CustomField value, taken from a series of tasks
		 */
		private String customFieldValue;
		/**
		 * The duration of the CustomField's Value
		 */
		private Duration duration;
		/**
		 * This CustomFieldTime is grouped
		 */
		private boolean group;

		/**
		 * Constructor of a CustomFieldTime
		 *
		 * @param customField the associated CustomField
		 * @param customFieldValue the value of the associated CustomField
		 * @param duration the duration of the CustomField's Value
		 * @param group true if the field should be grouped
		 */
		private CustomFieldTime(CustomField customField, String customFieldValue, Duration duration, Boolean group) {
			this.customField = customField;
			this.customFieldValue = customFieldValue;
			this.duration = duration;
			this.group = group;
		}

		/**
		 * Returns the associated CustomField
		 *
		 * @return the associated CustomField
		 */
		public CustomField getCustomField() {
			return customField;
		}

		/**
		 * Returns the CustomField's value
		 *
		 * @return the CustomField's value
		 */
		public String getCustomFieldValue() {
			return customFieldValue;
		}

		/**
		 * Adds a duration to this CustomFieldTime's duration
		 *
		 * @param add the duration to ass
		 */
		public void addDuration(Duration add) {
			duration = duration.plus(add);
		}

		/**
		 * Returns the duration for this CustomFieldTime
		 *
		 * @return the duration
		 */
		public Duration getDuration() {
			return duration;
		}

		/**
		 * Returns the duration for this CustomFieldTime in hours
		 *
		 * @return the duration in hours
		 */
		public Double getDurationHours() {
			return ((double) duration.getSeconds()) / 3600;
		}

		/**
		 * Returns true if this CustomFieldTime is grouped
		 *
		 * @return true if this CustomFieldTime is grouped
		 */
		public boolean isGroup() {
			return group;
		}

		/**
		 * Returns the CustomField's value for charts: if the item is assigned
		 * to a group, this will return the group name instead.
		 *
		 * @return the CustomField's value for charts
		 */
		public String getCustomFieldChartValue() {
			if (group)
				return messages.getString("CHART_GROUPED_ITEMS");
			else
				return customFieldValue;
		}
	}

	/**
	 * Creates a report
	 *
	 * @param dataManager the DataManager reference
	 */
	public Report(DataManager dataManager) {
		this.dataManager = dataManager;
	}

	/**
	 * Recursively deletes a path
	 *
	 * @param parent the parent path
	 * @throws IOException if deletion fails
	 */
	private void deleteTree(Path parent) throws IOException {
		Files.walkFileTree(parent, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if (exc == null) {
					Files.delete(dir);
					return FileVisitResult.CONTINUE;
				} else
					throw exc;
			}
		});
	}

	/**
	 * Returns a temporary directory to be used for storing report images
	 *
	 * @return the temporary directory to be used for storing report images
	 * @throws IOException when cannot create the temporary directory
	 */
	private Path getHTMLImagesDir() throws IOException {
		if (htmlImagesPath == null) {
			htmlImagesPath = Files.createTempDirectory("att"); //NOI18N
			Runnable deleteFile = new Runnable() {
				@Override
				public void run() {
					try {
						deleteTree(htmlImagesPath);
					} catch (IOException ex) {
						Logger.getLogger(Report.class.getName()).log(Level.SEVERE, null, ex);
					}
				}
			};
			Runtime.getRuntime().addShutdownHook(new Thread(deleteFile));
		}
		return htmlImagesPath;
	}

	/**
	 * Returns the report starting date
	 *
	 * @return the report starting date
	 */
	public LocalDate getStartDate() {
		return startDate;
	}

	/**
	 * Sets the report starting date. Only the date will be used, time is
	 * ignored.
	 *
	 * @param startDate the report starting date
	 */
	public void setStartDate(LocalDate startDate) {
		this.startDate = startDate;
	}

	/**
	 * Returns the report ending date.
	 *
	 * @return the report ending date
	 */
	public LocalDate getEndDate() {
		return endDate;
	}

	/**
	 * Sets the report ending date. Only the date will be used, time is ignored.
	 *
	 * @param endDate the report ending date
	 */
	public void setEndDate(LocalDate endDate) {
		this.endDate = endDate;
	}

	/**
	 * Progress property which indicates completion state of the report
	 * generation task. Is between [0..1].
	 *
	 * @return the progress property
	 */
	public DoubleProperty progressProperty() {
		return progress;
	}

	/**
	 * Returns the current locale date format
	 *
	 * @return the current locale date format
	 */
	protected String getDateFormat() {
		DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.SHORT);
		if (dateFormat instanceof SimpleDateFormat)
			return ((SimpleDateFormat) dateFormat).toPattern();
		else
			return null;
	}

	/**
	 * Returns the current locale time format
	 *
	 * @return the current locale time format
	 */
	protected String getTimeFormat() {
		DateFormat timeFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM);
		if (timeFormat instanceof SimpleDateFormat)
			return ((SimpleDateFormat) timeFormat).toPattern();
		else
			return null;
	}

	/**
	 * Returns the style for table titles
	 *
	 * @return the style for table titles
	 */
	protected StyleBuilder getTableTitleStyle() {
		return DynamicReports.stl.style()
				.setHorizontalAlignment(HorizontalAlignment.CENTER)
				.setVerticalAlignment(VerticalAlignment.MIDDLE)
				.setFontSize(20)
				.setBold(true);
	}

	/**
	 * Returns the style for column titles
	 *
	 * @return the style for column titles
	 */
	protected StyleBuilder getColumnTitleStyle() {
		return DynamicReports.stl.style()
				.setHorizontalAlignment(HorizontalAlignment.CENTER)
				.setVerticalAlignment(VerticalAlignment.MIDDLE)
				.setBorder(DynamicReports.stl.pen1Point())
				.setFontSize(14);
	}

	/**
	 * Returns the style for column titles
	 *
	 * @return the style for column titles
	 */
	protected StyleBuilder getPageHeaderStyle() {
		return DynamicReports.stl.style()
				.setHorizontalAlignment(HorizontalAlignment.CENTER)
				.setVerticalAlignment(VerticalAlignment.MIDDLE)
				.setFontSize(20)
				.setBold(true);
	}

	/**
	 * Returns the builder for the report title component
	 *
	 * @return the builder for the report title component
	 */
	protected ComponentBuilder getTitle() {
		String titleText = MessageFormat.format(messages.getString("TIMESHEET_HEADER"), new Object[]{
			DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(getStartDate()),
			DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(getEndDate())
		});
		StyleBuilder titleStyle = DynamicReports.stl.style()
				.setHorizontalAlignment(HorizontalAlignment.CENTER)
				.setVerticalAlignment(VerticalAlignment.MIDDLE)
				.setBorder(DynamicReports.stl.pen1Point())
				.setFontSize(24)
				.setBold(true);
		ComponentBuilder titleComponentBuilder = DynamicReports.cmp.text(titleText).setStyle(titleStyle);
		return titleComponentBuilder;
	}

	/**
	 * Returns the builder for the report's last page footer
	 *
	 * @return the builder for the report's last page footer
	 */
	protected ComponentBuilder getLastFooter() {
		AbstractSimpleExpression<String> lastPageFooterExpression = new AbstractSimpleExpression<String>() {
			@Override
			public String evaluate(ReportParameters rp) {
				return MessageFormat.format(messages.getString("REPORT_FOOTER"), new Object[]{new Date()});
			}
		};
		StyleBuilder lastPageFooterStyle = DynamicReports.stl.style()
				.setItalic(true)
				.setFontSize(10)
				.setVerticalAlignment(VerticalAlignment.MIDDLE)
				.setHorizontalAlignment(HorizontalAlignment.RIGHT);
		return DynamicReports.cmp.text(lastPageFooterExpression)
				.setStyle(lastPageFooterStyle)
				.setMarkup(Markup.HTML)
				.setHeight(20);
	}

	/**
	 * Returns the builder for the task's name and custom fields as a vertical
	 * list
	 *
	 * @return the builder for the task's name and custom fields as a vertical
	 * list
	 */
	protected VerticalListBuilder getTaskWithCustomFields() {
		//Create title and list
		StyleBuilder titleStyle = DynamicReports.stl.style()
				.setBold(true);
		VerticalListBuilder customFieldsList = DynamicReports.cmp.verticalList()
				.add(
						DynamicReports.cmp.text(DynamicReports.field("name", String.class)) //NOI18N
						.setStyle(titleStyle));
		//Style for custom field names
		StyleBuilder customFieldNameStyle = DynamicReports.stl.style()
				.setItalic(true);
		//Add custom columns
		for (CustomFieldAdapter customField : dataManager.getCustomFields()) {
			//Extract custom field
			DRIExpression<CustomField> customFieldExpression = new AbstractSimpleExpression<CustomField>() {
				private CustomField customField;

				public AbstractSimpleExpression<CustomField> setCustomField(CustomField customField) {
					this.customField = customField;
					return this;
				}

				@Override
				public CustomField evaluate(ReportParameters rp) {
					return customField;
				}
			}.setCustomField(customField.getCustomField());
			//Build the column
			customFieldsList = customFieldsList
					.add(
							DynamicReports.cmp.horizontalFlowList(
									DynamicReports.cmp.text(customFieldExpression).setValueFormatter(customFieldNameFormatter).setStyle(customFieldNameStyle).setRemoveLineWhenBlank(true),
									DynamicReports.cmp.text(customFieldExpression).setValueFormatter(customFieldValueFormatter).setRemoveLineWhenBlank(true)));
		}
		return customFieldsList;
	}

	/**
	 * Builds a report on all time segments: every time segment's task,
	 * description, start and end time
	 *
	 * @param timeSegments the list of time segments
	 * @return the report on time segments
	 */
	protected JasperReportBuilder buildTimeSegmentsReport(List<TimeSegment> timeSegments) {
		AbstractSimpleExpression<Date> startTimeExpression = new AbstractSimpleExpression<Date>() {
			@Override
			public Date evaluate(ReportParameters rp) {
				TimeSegment timeSegment = rp.getFieldValue("timeSegment"); //NOI18N
				Date clippedStartDate = timeSegment.getClippedStartTime(DateTools.getInstance().convertDateToStartOfDay(startDate), DateTools.getInstance().convertDateToEndOfDay(endDate));
				if (clippedStartDate != null)
					return clippedStartDate;
				else
					return null;
			}
		};
		AbstractSimpleExpression<Date> endTimeExpression = new AbstractSimpleExpression<Date>() {
			@Override
			public Date evaluate(ReportParameters rp) {
				TimeSegment timeSegment = rp.getFieldValue("timeSegment"); //NOI18N
				Date clippedEndDate = timeSegment.getClippedEndTime(DateTools.getInstance().convertDateToStartOfDay(startDate), DateTools.getInstance().convertDateToEndOfDay(endDate));
				if (clippedEndDate != null)
					return clippedEndDate;
				else
					return null;
			}
		};
		String header = messages.getString("FULL_TIME_REPORT");
		return DynamicReports.report()
				.pageHeader(DynamicReports.cmp.text(header).setStyle(getPageHeaderStyle()).setPrintWhenExpression(subreportPrintNotInFirstPageExpression))
				.title(DynamicReports.cmp.text(header).setTableOfContentsHeading(header).setStyle(getPageHeaderStyle()))
				.addField(DynamicReports.field("timeSegment", Date.class)) //NOI18N
				.sortBy(DynamicReports.asc(startTimeExpression))
				.columns(
						DynamicReports.col.column(messages.getString("TASK"), "owner.name", DynamicReports.type.stringType()), //NOI18N
						DynamicReports.col.column(messages.getString("SPECIFICS"), "description", DynamicReports.type.stringType()), //NOI18N
						DynamicReports.col.column(messages.getString("START_TIME"), startTimeExpression).setValueFormatter(dateTimeFormatter).setHorizontalAlignment(HorizontalAlignment.RIGHT),
						DynamicReports.col.column(messages.getString("END_TIME"), endTimeExpression).setValueFormatter(dateTimeFormatter).setHorizontalAlignment(HorizontalAlignment.RIGHT))
				.setHighlightDetailEvenRows(true)
				.setColumnTitleStyle(getColumnTitleStyle())
				.setDataSource(new JRBeanCollectionDataSource(timeSegments));
	}

	/**
	 * Builds a report on all tasks: every task's name, description, custom
	 * fields and total time
	 *
	 * @param tasks the list of tasks
	 * @return the report on tasks
	 */
	protected JasperReportBuilder buildTasksReport(List<Task> tasks) {
		AbstractSimpleExpression<Date> startTimeExpression = new AbstractSimpleExpression<Date>() {
			@Override
			public Date evaluate(ReportParameters rp) {
				Task task = rp.getFieldValue("task"); //NOI18N
				Date earliestStartDate = null;
				for (TimeSegment timeSegment : task.getTimeSegments()) {
					Date taskStartDate = timeSegment.getClippedStartTime(DateTools.getInstance().convertDateToStartOfDay(startDate), DateTools.getInstance().convertDateToEndOfDay(endDate));
					earliestStartDate = (earliestStartDate == null || (taskStartDate != null && timeSegment.getStartTime().before(earliestStartDate))) ? taskStartDate : earliestStartDate;
				}
				return earliestStartDate;
			}
		};
		AbstractSimpleExpression<Duration> totalTimeExpression = new AbstractSimpleExpression<Duration>() {
			@Override
			public Duration evaluate(ReportParameters rp) {
				Task task = rp.getFieldValue("task"); //NOI18N
				return task.getTotalTime(DateTools.getInstance().convertDateToStartOfDay(startDate), DateTools.getInstance().convertDateToEndOfDay(endDate));
			}
		};
		String header = messages.getString("TASKS");
		return DynamicReports.report()
				.pageHeader(DynamicReports.cmp.text(header).setStyle(getPageHeaderStyle()).setPrintWhenExpression(subreportPrintNotInFirstPageExpression))
				.title(DynamicReports.cmp.text(header).setTableOfContentsHeading(header).setStyle(getPageHeaderStyle()))
				.addField(DynamicReports.field("task", Task.class)) //NOI18N
				.sortBy(DynamicReports.asc(startTimeExpression))
				.sortBy(DynamicReports.desc(totalTimeExpression))
				.columns(
						DynamicReports.col.componentColumn(messages.getString("TASK"), getTaskWithCustomFields()),
						//DynamicReports.col.column("Task", "name", DynamicReports.type.stringType()),
						DynamicReports.col.column(messages.getString("DESCRIPTION"), "description", DynamicReports.type.stringType()), //NOI18N
						DynamicReports.col.column(messages.getString("TOTAL_TIME"), totalTimeExpression).setValueFormatter(periodFormatter).setHorizontalAlignment(HorizontalAlignment.RIGHT))
				.setHighlightDetailEvenRows(true)
				.setColumnTitleStyle(getColumnTitleStyle())
				.setDataSource(new JRBeanCollectionDataSource(tasks));
	}

	/**
	 * Builds a report on a custom field: the list of custom field values and
	 * the total for associated task. Creates a table and a pie chart.
	 *
	 * @param tasks the list of tasks
	 * @param customField the custom field
	 * @return the report on a custom field
	 */
	protected JasperReportBuilder buildCustomFieldReport(List<Task> tasks, CustomField customField) {
		//Prepare value-time map
		Map<String, CustomFieldTime> customFieldData = new TreeMap<>();
		for (Task task : tasks) {
			Duration duration = task.getTotalTime(DateTools.getInstance().convertDateToStartOfDay(startDate), DateTools.getInstance().convertDateToEndOfDay(endDate));
			String customFieldValue = task.getCustomField(customField);
			customFieldValue = customFieldValue != null ? customFieldValue : ""; //NOI18N
			if (customFieldData.containsKey(customFieldValue))
				customFieldData.get(customFieldValue).addDuration(duration);
			else
				customFieldData.put(customFieldValue, new CustomFieldTime(customField, customFieldValue, duration, false));
		}
		//Prepare report
		String header = MessageFormat.format(messages.getString("STATISTICS_HEADER"), new Object[]{customField.getCustomField().getName()});
		return DynamicReports.report()
				.pageHeader(DynamicReports.cmp.text(header).setStyle(getPageHeaderStyle()).setPrintWhenExpression(subreportPrintNotInFirstPageExpression))
				.title(DynamicReports.cmp.text(header).setTableOfContentsHeading(header).setStyle(getPageHeaderStyle()))
				.addField(DynamicReports.field("group", Date.class)) //NOI18N
				.summary(
						DynamicReports.cht.barChart()
						.setCategory(DynamicReports.field("customFieldChartValue", String.class)) //NOI18N
						.series(DynamicReports.cht.serie("durationHours", Double.class)) //NOI18N
						.setHeight(700)
						.setShowLegend(false)
						//.setLabelFormat("{0} ({1} hours)")
						.setOrientation(Orientation.HORIZONTAL))
				.sortBy(DynamicReports.desc(DynamicReports.field("durationHours", Double.class))) //NOI18N
				.columns(
						DynamicReports.col.column(messages.getString("FIELD"), "customFieldValue", DynamicReports.type.stringType()), //NOI18N
						DynamicReports.col.column(messages.getString("TOTAL_TIME"), "duration", Duration.class).setValueFormatter(periodFormatter).setHorizontalAlignment(HorizontalAlignment.RIGHT)) //NOI18N
				.setHighlightDetailEvenRows(true)
				.setColumnTitleStyle(getColumnTitleStyle())
				.setDataSource(new JRBeanCollectionDataSource(customFieldData.values()))
				.setSummaryWithPageHeaderAndFooter(true);
	}

	/**
	 * Builds a report on all custom fields's values.
	 *
	 * @param tasks the list of tasks
	 * @return the report on custom fields
	 */
	protected JasperReportBuilder buildCustomFieldsReport(List<Task> tasks) {
		List<ComponentBuilder> customFieldReports = new LinkedList<>();
		for (CustomFieldAdapter customField : dataManager.getCustomFields()) {
			customFieldReports.add(DynamicReports.cmp.pageBreak());
			customFieldReports.add(
					DynamicReports.cmp.subreport(
							buildCustomFieldReport(tasks, customField.getCustomField())));
		}
		return DynamicReports.report()
				//.pageHeader(DynamicReports.cmp.text(messages.getString("STATISTICS")).setStyle(getPageHeaderStyle()))
				.detail(customFieldReports.toArray(new ComponentBuilder[0]))
				.setDataSource(new JREmptyDataSource());
	}

	/**
	 * Builds a timesheet report: a list of all time segments for every day
	 *
	 * @param timeSegments the list of time segments
	 * @return the timesheet report
	 */
	protected JasperReportBuilder buildTimesheetReport(List<TimeSegment> timeSegments) {
		//Date-TimeSegment association list
		List<DateTimeSegment> dataSource = new LinkedList<>();
		{
			for (LocalDate localDate = startDate; !localDate.isAfter(endDate); localDate = localDate.plusDays(1)) {
				Date dayStart = DateTools.getInstance().convertDateToStartOfDay(localDate);
				Date dayEnd = DateTools.getInstance().convertDateToEndOfDay(localDate);
				for (TimeSegment timeSegment : timeSegments)
					if (!timeSegment.getClippedDuration(dayStart, dayEnd).equals(Duration.ZERO))
						dataSource.add(new DateTimeSegment(dayStart, dayEnd, timeSegment));
			}
		}

		//Style for day headers
		StyleBuilder dayHeaderStyle = DynamicReports.stl.style()
				.setBold(true)
				.setAlignment(HorizontalAlignment.CENTER, VerticalAlignment.MIDDLE);

		AbstractSimpleExpression<String> dateTitleExpression = new AbstractSimpleExpression<String>() {
			@Override
			public String evaluate(ReportParameters rp) {
				Date date = rp.getFieldValue("date"); //NOI18N
				return MessageFormat.format(messages.getString("DATE_FORMAT"), new Object[]{date});
			}
		};
		CustomGroupBuilder dateGroup = DynamicReports.grp.group(dateTitleExpression)
				.setStyle(dayHeaderStyle)
				.setPadding(0)
				.setAddToTableOfContents(false);
		String header = messages.getString("TIMESHEET");
		return DynamicReports.report()
				.pageHeader(DynamicReports.cmp.text(header).setStyle(getPageHeaderStyle()).setPrintWhenExpression(subreportPrintNotInFirstPageExpression))
				.title(DynamicReports.cmp.text(header).setTableOfContentsHeading(header).setStyle(getPageHeaderStyle()))
				.columns(
						DynamicReports.col.column(messages.getString("TASK"), "timeSegment.owner.name", DynamicReports.type.stringType()), //NOI18N
						DynamicReports.col.column(messages.getString("SPECIFICS"), "timeSegment.description", DynamicReports.type.stringType()), //NOI18N
						//DynamicReports.col.column("Duration", "duration", DynamicReports.type.stringType()),
						DynamicReports.col.column(messages.getString("HOURS"), "durationHours", DynamicReports.type.doubleType())) //NOI18N
				.groupBy(dateGroup)
				.sortBy(DynamicReports.asc("date", Date.class)) //NOI18N
				.setHighlightDetailEvenRows(true)
				.setColumnTitleStyle(getColumnTitleStyle())
				.setDataSource(new JRBeanCollectionDataSource(dataSource));
	}

	/**
	 * Returns the report in HTML form
	 *
	 * @return the report in HTML form
	 */
	public String getReportHTML() {
		return reportHTML;
	}

	/**
	 * Saves report to PDF
	 *
	 * @param outputFile destination file
	 * @throws FileNotFoundException if path is incorrect
	 * @throws DRException when an internal error occurs
	 */
	public void savePdfReport(File outputFile) throws FileNotFoundException, DRException {
		JasperPdfExporterBuilder pdfExporter = Exporters.pdfExporter(outputFile);
		report.toPdf(pdfExporter);
	}

	/**
	 * Builds the report in DynamicReports form; prepares an HTML report for
	 * preview. Only the time between startDate and endDate will be used.
	 */
	public void buildReport() {
		try {
			//Get data
			//progressProperty().set(0);
			progressProperty().set(-1);
			ReportQuery reportQuery = new ReportQuery(dataManager.getPersistenceHelper());
			reportQuery.setStartDate(startDate);
			reportQuery.setEndDate(endDate);
			List<TimeSegment> timeSegments = reportQuery.queryTimeSegments();
			List<Task> tasks = reportQuery.queryTasks();

			//Build the report
			//progressProperty().set(0.2);
			//Prepare exporter
			ByteArrayOutputStream stream = new ByteArrayOutputStream();
			JasperHtmlExporterBuilder htmlExporter
					= Exporters.htmlExporter(stream)
					.setOutputImagesToDir(true)
					.setImagesURI(getHTMLImagesDir().toUri().toString())
					.setImagesDirName(getHTMLImagesDir().toString())
					.setUsingImagesToAlign(false);

			//Table of contents after title
			TableOfContentsCustomizer tableOfContentsAfterTitle = new TableOfContentsCustomizer() {
				@Override
				public void customize() {
					report.title(
							getTitle(),
							DynamicReports.cmp.verticalGap(20));
					super.customize();
				}
			};
			//Prepare full report
			report = DynamicReports.report()
					.setPageFormat(PageType.A4, PageOrientation.PORTRAIT)
					.tableOfContents(tableOfContentsAfterTitle)
					.detail(
							DynamicReports.cmp.verticalGap(20),
							DynamicReports.cmp.subreport(buildTasksReport(tasks)),
							DynamicReports.cmp.subreport(buildCustomFieldsReport(tasks)),
							DynamicReports.cmp.pageBreak(),
							DynamicReports.cmp.subreport(buildTimeSegmentsReport(timeSegments)),
							DynamicReports.cmp.pageBreak(),
							DynamicReports.cmp.subreport(buildTimesheetReport(timeSegments)))
					.lastPageFooter(getLastFooter())
					.setDataSource(new JREmptyDataSource())
					.toHtml(htmlExporter);
			reportHTML = stream.toString("utf-8"); //NOI18N
		} catch (UnsupportedEncodingException | DRException ex) {
			Logger.getLogger(Report.class.getName()).log(Level.SEVERE, null, ex);
			ExceptionLogger.getInstance().showException(null, ex);
		} catch (Throwable ex) {
			Logger.getLogger(Report.class.getName()).log(Level.SEVERE, null, ex);
			ExceptionLogger.getInstance().showException(null, ex);
		}
	}
}
