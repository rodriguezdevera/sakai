package org.sakaiproject.gradebookng.tool.panels.importExport;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.wicket.Component;
import org.apache.wicket.markup.html.form.Button;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.upload.FileUpload;
import org.apache.wicket.markup.html.form.upload.FileUploadField;
import org.apache.wicket.markup.html.link.DownloadLink;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.apache.wicket.util.lang.Bytes;
import org.apache.wicket.util.time.Duration;
import org.sakaiproject.gradebookng.business.GradebookNgBusinessService;
import org.sakaiproject.gradebookng.business.helpers.ImportGradesHelper;
import org.sakaiproject.gradebookng.business.model.GbGradeInfo;
import org.sakaiproject.gradebookng.business.model.GbStudentGradeInfo;
import org.sakaiproject.gradebookng.business.model.ImportedGradeWrapper;
import org.sakaiproject.gradebookng.business.model.ProcessedGradeItem;
import org.sakaiproject.gradebookng.tool.model.ImportWizardModel;
import org.sakaiproject.gradebookng.tool.pages.GradebookPage;
import org.sakaiproject.service.gradebook.shared.Assignment;

import au.com.bytecode.opencsv.CSVWriter;
import lombok.extern.apachecommons.CommonsLog;

/**
 * Created by chmaurer on 1/22/15.
 */
@CommonsLog
public class GradeImportUploadStep extends Panel {

	// list of mimetypes for each category. Must be compatible with the parser
	private static final String[] XLS_MIME_TYPES = { "application/vnd.ms-excel",
			"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" };
	private static final String[] CSV_MIME_TYPES = { "text/csv" };

	private final String panelId;
	private List<Assignment> assignments;
	private List<GbStudentGradeInfo> grades;

	@SpringBean(name = "org.sakaiproject.gradebookng.business.GradebookNgBusinessService")
	protected GradebookNgBusinessService businessService;

	public GradeImportUploadStep(final String id) {
		super(id);
		this.panelId = id;
	}

	@Override
	public void onInitialize() {
		super.onInitialize();

		// get list of assignments. this allows us to build the columns and then fetch the grades for each student for each assignment from
		// the map
		this.assignments = this.businessService.getGradebookAssignments();

		// get the grade matrix
		this.grades = this.businessService.buildGradeMatrix(this.assignments);

		add(new DownloadLink("downloadFullGradebook", new LoadableDetachableModel<File>() {

			@Override
			protected File load() {
				return buildFile(true);

			}
		}).setCacheDuration(Duration.NONE).setDeleteAfterDownload(true));

		add(new UploadForm("form"));
	}

	private File buildFile(final boolean includeGrades) {
		File tempFile;
		try {
			// TODO - add the site name to the file?
			tempFile = File.createTempFile("gradebookTemplate", ".csv");
			final FileWriter fw = new FileWriter(tempFile);
			final CSVWriter csvWriter = new CSVWriter(fw);

			// Create csv header
			final List<String> header = new ArrayList<String>();
			header.add("Student ID");
			header.add("Student Name");

			for (final Assignment assignment : this.assignments) {
				final String assignmentPoints = assignment.getPoints().toString();
				header.add(assignment.getName() + " [" + StringUtils.removeEnd(assignmentPoints, ".0") + "]");
				header.add("*/ " + assignment.getName() + " Comments */");
			}

			csvWriter.writeNext(header.toArray(new String[] {}));

			for (final GbStudentGradeInfo studentGradeInfo : this.grades) {
				final List<String> line = new ArrayList<String>();
				line.add(studentGradeInfo.getStudentEid());
				line.add(studentGradeInfo.getStudentLastName() + ", " + studentGradeInfo.getStudentFirstName());
				if (includeGrades) {
					for (final Assignment assignment : this.assignments) {
						final GbGradeInfo gradeInfo = studentGradeInfo.getGrades().get(assignment.getId());
						if (gradeInfo != null) {
							line.add(StringUtils.removeEnd(gradeInfo.getGrade(), ".0"));
							line.add(gradeInfo.getGradeComment());
						} else {
							// Need to account for no grades
							line.add(null);
							line.add(null);
						}
					}
				}
				csvWriter.writeNext(line.toArray(new String[] {}));
			}

			csvWriter.close();
			fw.close();
		} catch (final IOException e) {
			throw new RuntimeException(e);
		}
		return tempFile;

	}

	/*
	 * Upload form
	 */
	private class UploadForm extends Form<Void> {

		FileUploadField fileUploadField;

		public UploadForm(final String id) {
			super(id);

			setMultiPart(true);
			setMaxSize(Bytes.megabytes(2));

			this.fileUploadField = new FileUploadField("upload");
			add(this.fileUploadField);

			add(new Button("continuebutton"));

			final Button cancel = new Button("cancelbutton") {
				@Override
				public void onSubmit() {
					// info("Cancel was pressed!");
					setResponsePage(new GradebookPage());
				}
			};
			cancel.setDefaultFormProcessing(false);
			add(cancel);
		}

		@Override
		public void onSubmit() {

			final FileUpload upload = this.fileUploadField.getFileUpload();
			if (upload != null) {

				try {
					log.debug("file upload success");
					// get all users
					final Map<String, String> userMap = makeUserMap(GradeImportUploadStep.this.grades);

					// turn file into list
					final ImportedGradeWrapper importedGradeWrapper = parseImportedGradeFile(upload.getInputStream(),
							upload.getContentType(), userMap);

					final List<ProcessedGradeItem> processedGradeItems = ImportGradesHelper.processImportedGrades(importedGradeWrapper,
							GradeImportUploadStep.this.assignments, GradeImportUploadStep.this.grades);

					// if null, the file was of the incorrect type
					// if empty there are no users
					if (processedGradeItems == null || processedGradeItems.isEmpty()) {
						error(getString("error.parse.upload"));
					} else {
						// GO TO NEXT PAGE
						log.debug(processedGradeItems.size());

						// repaint panel
						final ImportWizardModel importWizardModel = new ImportWizardModel();
						importWizardModel.setProcessedGradeItems(processedGradeItems);
						final Component newPanel = new GradeItemImportSelectionStep(GradeImportUploadStep.this.panelId,
								Model.of(importWizardModel));

						newPanel.setOutputMarkupId(true);
						GradeImportUploadStep.this.replaceWith(newPanel);

					}

				} catch (final IOException e) {
					e.printStackTrace();
				}

			}

		}
	}

	/**
	 * Create a map so that we can use the user's eid (from the imported file) to lookup their uuid (used to store the grade by the backend
	 * service)
	 *
	 * @param grades
	 * @return Map where the user's eid is the key and the uuid is the value
	 */
	private Map<String, String> makeUserMap(final List<GbStudentGradeInfo> grades) {
		final Map<String, String> userMap = new HashMap<String, String>();

		for (final GbStudentGradeInfo studentGradeInfo : grades) {
			userMap.put(studentGradeInfo.getStudentEid(), studentGradeInfo.getStudentUuid());
		}
		return userMap;
	}

	public ImportedGradeWrapper parseImportedGradeFile(final InputStream is, final String mimetype, final Map<String, String> userMap) {

		// determine file type and delegate
		if (ArrayUtils.contains(CSV_MIME_TYPES, mimetype)) {
			return ImportGradesHelper.parseCsv(is, userMap);
		} else if (ArrayUtils.contains(XLS_MIME_TYPES, mimetype)) {
			return ImportGradesHelper.parseXls(is, userMap);
		} else {
			log.error("Invalid file type for grade import: " + mimetype);
		}
		return null;
	}
}
