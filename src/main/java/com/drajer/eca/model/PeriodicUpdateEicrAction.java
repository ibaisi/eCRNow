package com.drajer.eca.model;

import java.util.List;

import org.hl7.fhir.r4.model.PlanDefinition.ActionRelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.drajer.cdafromR4.CdaEicrGeneratorFromR4;
import com.drajer.cdafromdstu2.CdaEicrGenerator;
import com.drajer.eca.model.EventTypes.EcrActionTypes;
import com.drajer.eca.model.EventTypes.JobStatus;
import com.drajer.eca.model.EventTypes.WorkflowEvent;
import com.drajer.ecrapp.service.WorkflowService;
import com.drajer.sof.model.Dstu2FhirData;
import com.drajer.sof.model.FhirData;
import com.drajer.sof.model.LaunchDetails;
import com.drajer.sof.model.R4FhirData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class PeriodicUpdateEicrAction extends AbstractAction {

	private final Logger logger = LoggerFactory.getLogger(PeriodicUpdateEicrAction.class);
	
	@Override
	public void print() {
		
		logger.info(" **** Printing PeriodicUpdateEicrAction **** ");
		printBase();
		logger.info(" **** End Printing PeriodicUpdateEicrAction **** ");
	}
	@Override
	public void execute(Object obj, WorkflowEvent launchType) {
		
		logger.info(" **** START Executing Periodic Update Eicr Action **** ");

		if (obj instanceof LaunchDetails) {

			LaunchDetails details = (LaunchDetails) obj;
			ObjectMapper mapper = new ObjectMapper();
			PatientExecutionState state = null;
			PeriodicUpdateEicrStatus status = new PeriodicUpdateEicrStatus();

			try {
				state = mapper.readValue(details.getStatus(), PatientExecutionState.class);
				status.setActionId(getActionId());
			}  catch (JsonProcessingException e1) {
				String msg = "Unable to read/write execution state";
				handleException(e1, logger, msg);
			}

			logger.info(" Executing Periodic Update Eicr Action , Prior Execution State : = {}" , details.getStatus());

			// Handle Conditions
			Boolean conditionsMet = true;
			conditionsMet = handleConditions(details, conditionsMet);

			// PreConditions Met, then process related actions.
			Boolean relatedActsDone = true;
			if (conditionsMet) {
				
				logger.info(" PreConditions have been Met, evaluating Related Actions. ");

				if (getRelatedActions() != null && getRelatedActions().size() > 0) {

					List<RelatedAction> racts = getRelatedActions();

					for (RelatedAction ract : racts) {

						// Check for all actions AFTER which this action has to be executed for completion.
						if (ract.getRelationship() == ActionRelationshipType.AFTER) {

							// check if the action is completed.
							String actionId = ract.getRelatedAction().getActionId();

							if (!state.hasActionCompleted(actionId)) {

								logger.info(
										" Action {} is not completed , hence this action has to wait ",actionId);
								relatedActsDone = false;
							}
							else {
								
								logger.info(" Related Action has been completed : {}" , actionId);
								
								// Check if there is any timing constraint that needs to be handled.
								if(ract.getDuration() != null && 
									state.getPeriodicUpdateJobStatus() == JobStatus.NOT_STARTED) {
									
									// Duration is not null, meaning that the create action has to be delayed by the duration.
									logger.info(" Schedule the job for Priodic Update EICR based on the duration.");
								
									try {
										
										WorkflowService.scheduleJob(details.getId(), ract.getDuration(), EcrActionTypes.PERIODIC_UPDATE_EICR);
										
										getAndSetPeriodicUpdates(state,details,status,mapper);
										
										// No need to continue as the job will take over execution.
										
										logger.info(" **** END Executing Periodic Update Eicr Action **** ");
										return;
									} catch (JsonProcessingException e) { 
										String msg = "Unable to read/write execution state";
										handleException(e, logger, msg);
									}
								}
								else {
									
									logger.info( " No need to scheuled job as it has already been scheduled or completed. ");
								}
							}
						}
						else {
							logger.info(" Action {} is related via {}",ract.getRelatedAction().getActionId(),ract.getRelationship());
							
						}
					}
				}
				
				// Check Timing Data , No need to check if the state is already scheduled meaning the
				// job was scheduled already.
				if (relatedActsDone) {
					
					logger.info(" All Related Actions are completed ");

					// Timing constraints are applicable if this job has not started, once it is started
					// the State Machine has to manage the execution.
					if (state.getPeriodicUpdateJobStatus() == JobStatus.NOT_STARTED) {
						
						logger.info(" Related Actions Done and this action has not started ");
						
						if (getTimingData() != null && getTimingData().size() > 0) {
							
							logger.info(" Timing Data is present , so create a job based on timing data.");
							List<TimingSchedule> tsjobs = getTimingData();

							for (TimingSchedule ts : tsjobs) {

								// TBD : Setup job using TS Timing after testing so that we can test faster.
								// For now setup a default job with 10 seconds.
								try {
									
									WorkflowService.scheduleJob(details.getId(), ts, EcrActionTypes.PERIODIC_UPDATE_EICR);		
									
									getAndSetPeriodicUpdates(state,details,status,mapper);
									
									// No need to continue as the job will take over execution.
									logger.info(" **** End Executing Periodic Update Eicr Action **** ");
									return;
								} catch (JsonProcessingException e) { 
									String msg = "Unable to read/write execution state";
									handleException(e, logger, msg);
								}

								
							}

						}
						
						logger.info(" No job to schedule since there is no timing data ");
					}
					else if (state.getPeriodicUpdateJobStatus() == JobStatus.SCHEDULED &&
							 !state.getCreateEicrStatus().getEicrCreated() && 
							 state.getCloseOutEicrStatus().getJobStatus() != JobStatus.COMPLETED) {
					
						// Do this only if the job is scheduled.
						logger.info(" Creating the Periodic Update EICR since the job has been scheduled ");
						
						// Check Trigger Codes again in case the data has changed.
						PatientExecutionState newState = recheckTriggerCodes(details, launchType);
						
						if(newState.getMatchTriggerStatus().getTriggerMatchStatus() && 
						   newState.getMatchTriggerStatus().getMatchedCodes() != null && 
						   newState.getMatchTriggerStatus().getMatchedCodes().size() > 0) {

							// Since the job has started, Execute the job.
							// Call the Loading Queries and create eICR.
							if (ActionRepo.getInstance().getLoadingQueryService() != null) {

								logger.info(" Getting necessary data from Loading Queries ");
								FhirData data = ActionRepo.getInstance().getLoadingQueryService().getData(details, details.getStartDate(), details.getEndDate());

								String eICR = null;

								if (data != null && data instanceof Dstu2FhirData) {

									Dstu2FhirData dstu2Data = (Dstu2FhirData) data;
									eICR = CdaEicrGenerator.convertDstu2FhirBundletoCdaEicr(dstu2Data, details);

									// Create the object for persistence.
									createPersistenceObjectToCreateEicrAction(eICR, newState, details, mapper);

									logger.info(" **** Printing Eicr from Periodic Update EICR ACTION **** ");

									logger.info(eICR);

									saveDataToTheFile("_PeriodicUpdateEicrAction", details, eICR);

									logger.info(" **** End Printing Eicr from Periodic Update EICR ACTION **** ");

									
									
								}
								else if(data != null && data instanceof R4FhirData) {
									logger.info("Creating eICR based on FHIR R4 ");
									R4FhirData r4Data = (R4FhirData) data;
									eICR = CdaEicrGeneratorFromR4.convertR4FhirBundletoCdaEicr(r4Data, details);
									

									// Create the object for persistence.
									createPersistenceObjectToCreateEicrAction(eICR, newState, details, mapper);

									logger.info(" **** Printing Eicr from CREATE EICR ACTION **** ");

									logger.info(eICR);
									
									saveDataToTheFile("_PeriodicUpdateEicrAction",details,eICR);

									logger.info(" **** End Printing Eicr from CREATE EICR ACTION **** ");
								}
								else {

									String msg = "No Fhir Data retrieved to CREATE EICR.";
									logger.error(msg);

									throw new RuntimeException(msg);
								}


							}
							else {

								String msg = "System Startup Issue, Spring Injection not functioning properly, loading service is null.";
								logger.error(msg);

								throw new RuntimeException(msg);
							}
						}// Check if Trigger Code Match found 
						else {
							
							logger.info(" **** Trigger Code did not match, hence not creating EICR **** ");
							
							// Schedule job again.
							if (getTimingData() != null && getTimingData().size() > 0) {
								
								logger.info(" Timing Data is present , so create a job based on timing data.");
								List<TimingSchedule> tsjobs = getTimingData();

								for (TimingSchedule ts : tsjobs) {

									// TBD : Setup job using TS Timing after testing so that we can test faster.
									// For now setup a default job with 10 seconds.
									try {
										
										WorkflowService.scheduleJob(details.getId(), ts, EcrActionTypes.PERIODIC_UPDATE_EICR);		
										
										getAndSetPeriodicUpdates(state,details,status,mapper);
										
										// No need to continue as the job will take over execution.
										logger.info(" **** End Executing Periodic Update Eicr Action **** ");
										return;
									} catch (JsonProcessingException e) { 
										String msg = "Unable to read/write execution state";
										handleException(e, logger, msg);
									}

									
								}

							}
						}
					}
					else {
						logger.info(" Periodic Update not needed , due to which EICR will not be created. ");
					}

				}
				else {
					logger.info(" Related Actions are not completed, hence EICR will not be created. ");
				}
				
			}
			else {
				
				logger.info(" Conditions not met, hence EICR will not be created. ");
			}
		}
		else {
			
			String msg = "Invalid Object passed to Execute method, Launch Details expected, found : " + obj.getClass().getName();
			logger.error(msg);
			
			throw new RuntimeException(msg);
			
		}
		
		logger.info(" **** END Executing Create Eicr Action after completing normal execution. **** ");
	}
	
	public void getAndSetPeriodicUpdates(PatientExecutionState state,LaunchDetails details,PeriodicUpdateEicrStatus status,ObjectMapper mapper) throws JsonProcessingException {
		state.setPeriodicUpdateJobStatus(JobStatus.SCHEDULED);	
		state.getPeriodicUpdateStatus().add(status);
		details.setStatus(mapper.writeValueAsString(state));
		
	}

}
