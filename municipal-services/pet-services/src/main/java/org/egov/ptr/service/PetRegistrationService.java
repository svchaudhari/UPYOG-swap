package org.egov.ptr.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.Role;
import org.egov.ptr.config.PetConfiguration;
import org.egov.ptr.models.ApplicationDetail;
import org.egov.ptr.models.Demand;
import org.egov.ptr.models.PetApplicationSearchCriteria;
import org.egov.ptr.models.PetRegistrationActionRequest;
import org.egov.ptr.models.PetRegistrationActionResponse;
import org.egov.ptr.models.PetRegistrationApplication;
import org.egov.ptr.models.PetRegistrationRequest;
import org.egov.ptr.models.collection.Bill;
import org.egov.ptr.models.collection.BillResponse;
import org.egov.ptr.models.collection.BillSearchCriteria;
import org.egov.ptr.models.collection.GenerateBillCriteria;
import org.egov.ptr.models.workflow.BusinessServiceResponse;
import org.egov.ptr.models.workflow.State;
import org.egov.ptr.producer.Producer;
import org.egov.ptr.repository.PetRegistrationRepository;
import org.egov.ptr.repository.ServiceRequestRepository;
import org.egov.ptr.util.PTRConstants;
import org.egov.ptr.validator.PetApplicationValidator;
import org.egov.ptr.web.contracts.RequestInfoWrapper;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class PetRegistrationService {

	@Autowired
	private Producer producer;

	@Autowired
	private PetConfiguration config;

	@Autowired
	private EnrichmentService enrichmentService;

	@Autowired
	private PetApplicationValidator validator;

	@Autowired
	private UserService userService;

	@Autowired
	private WorkflowService wfService;

	@Autowired
	private DemandService demandService;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private PetRegistrationRepository petRegistrationRepository;

	@Autowired
	private BillingService billingService;

	@Autowired
	private ServiceRequestRepository serviceRequestRepository;

	/**
	 * Enriches the Request and pushes to the Queue
	 *
	 */
	public List<PetRegistrationApplication> registerPtrRequest(PetRegistrationRequest petRegistrationRequest) {

		validator.validatePetApplication(petRegistrationRequest);
		enrichmentService.enrichPetApplication(petRegistrationRequest);

		// Enrich/Upsert user in upon pet registration
		// userService.callUserService(petRegistrationRequest); need to build the method
		wfService.updateWorkflowStatus(petRegistrationRequest);
		producer.push(config.getCreatePtrTopic(), petRegistrationRequest);

		return petRegistrationRequest.getPetRegistrationApplications();
	}

	public List<PetRegistrationApplication> searchPtrApplications(RequestInfo requestInfo,
			PetApplicationSearchCriteria petApplicationSearchCriteria) {

		validateAndEnrichSearchCriteria(requestInfo, petApplicationSearchCriteria);
		
		List<PetRegistrationApplication> applications = petRegistrationRepository
				.getApplications(petApplicationSearchCriteria);

		if (CollectionUtils.isEmpty(applications))
			return new ArrayList<>();

		return applications;
	}

	private void validateAndEnrichSearchCriteria(RequestInfo requestInfo,
			PetApplicationSearchCriteria petApplicationSearchCriteria) {
		
		// search criteria for CITIZEN
		if(null != requestInfo && null != requestInfo.getUserInfo()
				&& StringUtils.equalsAnyIgnoreCase(requestInfo.getUserInfo().getType(), PTRConstants.USER_TYPE_CITIZEN)) {
			petApplicationSearchCriteria.setCreatedBy(requestInfo.getUserInfo().getUuid());
		}
		
		// search criteria for EMPLOYEE
		if(null != requestInfo && null != requestInfo.getUserInfo()
				&& StringUtils.equalsAnyIgnoreCase(requestInfo.getUserInfo().getType(), PTRConstants.USER_TYPE_EMPLOYEE)) {
			if(petApplicationSearchCriteria == null || StringUtils.isEmpty(petApplicationSearchCriteria.getTenantId())) {
				throw new CustomException("TENANTID_MANDATORY", "TenantId is mandatory for employee to search registrations.");
			}
			
			List<String> listOfStatus = getAccountStatusListByRoles(petApplicationSearchCriteria.getTenantId(), requestInfo.getUserInfo().getRoles());
			if(CollectionUtils.isEmpty(listOfStatus)) {
				throw new CustomException("SEARCH_ACCOUNT_BY_ROLES","Search can't be performed by this Employee due to lack of roles.");
			}
			petApplicationSearchCriteria.setStatus(listOfStatus);
		}
		
		
		
	}
	
	private List<String> getAccountStatusListByRoles(String tenantId, List<Role> roles) {
		
		List<String> rolesWithinTenant = getRolesByTenantId(tenantId, roles);	
		Set<String> statusWithRoles = new HashSet();
		
		rolesWithinTenant.stream().forEach(role -> {
			
			if(StringUtils.equalsIgnoreCase(role, PTRConstants.USER_ROLE_PTR_VERIFIER)) {
				statusWithRoles.add(PTRConstants.APPLICATION_STATUS_PENDINGFORVERIFICATION);
			}else if(StringUtils.equalsIgnoreCase(role, PTRConstants.USER_ROLE_PTR_APPROVER)) {
				statusWithRoles.add(PTRConstants.APPLICATION_STATUS_PENDINGFORAPPROVAL);
			}
			
		});
		
		return new ArrayList<>(statusWithRoles);
	}
	

	private List<String> getRolesByTenantId(String tenantId, List<Role> roles) {

		List<String> roleCodes = roles.stream()
				.filter(role -> StringUtils.equalsIgnoreCase(role.getTenantId(), tenantId)).map(role -> role.getCode())
				.collect(Collectors.toList());
		return roleCodes;
	}


	public PetRegistrationApplication updatePtrApplication(PetRegistrationRequest petRegistrationRequest) {
		PetRegistrationApplication existingApplication = validator
				.validateApplicationExistence(petRegistrationRequest.getPetRegistrationApplications().get(0));
		existingApplication.setWorkflow(petRegistrationRequest.getPetRegistrationApplications().get(0).getWorkflow());
		existingApplication.setIsOnlyWorkflowCall(petRegistrationRequest.getPetRegistrationApplications().get(0).getIsOnlyWorkflowCall());
		
		if(BooleanUtils.isTrue(petRegistrationRequest.getPetRegistrationApplications().get(0).getIsOnlyWorkflowCall())
				&& null != petRegistrationRequest.getPetRegistrationApplications().get(0).getWorkflow()) {
			String status = getStatusOrAction(petRegistrationRequest.getPetRegistrationApplications().get(0).getWorkflow().getAction(), false);
			petRegistrationRequest.setPetRegistrationApplications(Collections.singletonList(existingApplication));
			petRegistrationRequest.getPetRegistrationApplications().get(0).setStatus(status);
		}
		
//		petRegistrationRequest.setPetRegistrationApplications(Collections.singletonList(existingApplication));

		enrichmentService.enrichPetApplicationUponUpdate(petRegistrationRequest);

		if (petRegistrationRequest.getPetRegistrationApplications().get(0).getWorkflow().getAction()
				.equals(PTRConstants.WORKFLOW_ACTION_RETURN_TO_INITIATOR_FOR_PAYMENT)) {
			
			// create demands
			List<Demand> savedDemands = demandService.createDemand(petRegistrationRequest);

	        if(CollectionUtils.isEmpty(savedDemands)) {
	            throw new CustomException("INVALID_CONSUMERCODE","Bill not generated due to no Demand found for the given consumerCode");
	        }

			// fetch/create bill
            GenerateBillCriteria billCriteria = GenerateBillCriteria.builder()
            									.tenantId(petRegistrationRequest.getPetRegistrationApplications().get(0).getTenantId())
            									.businessService("pet-services")
            									.consumerCode(petRegistrationRequest.getPetRegistrationApplications().get(0).getApplicationNumber()).build();
            BillResponse billResponse = billingService.generateBill(petRegistrationRequest.getRequestInfo(),billCriteria);
            
		}
		wfService.updateWorkflowStatus(petRegistrationRequest);
		producer.push(config.getUpdatePtrTopic(), petRegistrationRequest);

		return petRegistrationRequest.getPetRegistrationApplications().get(0);
	}
	

	public String getStatusOrAction(String action, Boolean fetchValue) {
		
		Map<String, String> map = new HashMap<>();
		
		map.put(PTRConstants.WORKFLOW_ACTION_INITIATE, PTRConstants.APPLICATION_STATUS_INITIATED);
        map.put(PTRConstants.WORKFLOW_ACTION_FORWARD_TO_VERIFIER, PTRConstants.APPLICATION_STATUS_PENDINGFORVERIFICATION);
        map.put(PTRConstants.WORKFLOW_ACTION_VERIFY, PTRConstants.APPLICATION_STATUS_PENDINGFORAPPROVAL);
        map.put(PTRConstants.WORKFLOW_ACTION_RETURN_TO_INITIATOR_FOR_PAYMENT, PTRConstants.APPLICATION_STATUS_PENDINGFORPAYMENT);
        map.put(PTRConstants.WORKFLOW_ACTION_RETURN_TO_INITIATOR, PTRConstants.APPLICATION_STATUS_PENDINGFORMODIFICATION);
        map.put(PTRConstants.WORKFLOW_ACTION_FORWARD_TO_APPROVER, PTRConstants.APPLICATION_STATUS_PENDINGFORAPPROVAL);
        map.put(PTRConstants.WORKFLOW_ACTION_APPROVE, PTRConstants.APPLICATION_STATUS_APPROVED);
		
		if(!fetchValue){
			// return key
			for (Map.Entry<String, String> entry : map.entrySet()) {
		        if (entry.getValue().equals(action)) {
		            return entry.getKey();
		        }
		    }
		}
		// return value
		return map.get(action);
	}

	public Object enrichResponseDetail(List<PetRegistrationApplication> applications) {
		
		Map<String, Object> responseDetail = new HashMap<>();

		responseDetail.put("applicationInitiated", applications.stream().filter(application -> StringUtils
				.equalsIgnoreCase(application.getStatus(), PTRConstants.APPLICATION_STATUS_INITIATED)).count());
		responseDetail.put("applicationApplied", applications.stream().filter(application -> StringUtils
				.equalsAnyIgnoreCase(application.getStatus(), PTRConstants.APPLICATION_STATUS_PENDINGFORVERIFICATION
															, PTRConstants.APPLICATION_STATUS_PENDINGFORAPPROVAL
															, PTRConstants.APPLICATION_STATUS_PENDINGFORMODIFICATION)).count());
		responseDetail.put("applicationPendingForPayment", applications.stream().filter(application -> StringUtils
				.equalsIgnoreCase(application.getStatus(), PTRConstants.APPLICATION_STATUS_PENDINGFORPAYMENT)).count());
		responseDetail.put("applicationRejected", applications.stream().filter(application -> StringUtils
				.equalsIgnoreCase(application.getStatus(), PTRConstants.APPLICATION_STATUS_REJECTED)).count());
		responseDetail.put("applicationApproved", applications.stream().filter(application -> StringUtils
				.equalsIgnoreCase(application.getStatus(), PTRConstants.APPLICATION_STATUS_APPROVED)).count());

		return responseDetail;
	}

	public PetRegistrationActionResponse getApplicationDetails(
			PetRegistrationActionRequest ptradeLicenseActionRequest) {
		
		if(CollectionUtils.isEmpty(ptradeLicenseActionRequest.getApplicationNumbers())) {
			throw new CustomException("INVALID REQUEST","Provide Application Number.");
		}
		
		PetRegistrationActionResponse tradeLicenseActionResponse = PetRegistrationActionResponse.builder()
																.applicationDetails(new ArrayList<>())
																.build();
//		Map<String, Object> totalTaxMap = new HashMap<>();
		
		ptradeLicenseActionRequest.getApplicationNumbers().stream().forEach(applicationNumber -> {
			
			// search application number
			PetApplicationSearchCriteria criteria = PetApplicationSearchCriteria.builder()
					.applicationNumber(applicationNumber)
					.build();
			List<PetRegistrationApplication> petApplications = petRegistrationRepository.getApplications(criteria);
			PetRegistrationApplication petRegistrationApplication = null != petApplications ? petApplications.get(0): null;
			

			ApplicationDetail applicationDetail = getApplicationBillUserDetail(petRegistrationApplication, ptradeLicenseActionRequest.getRequestInfo());
			
			
			tradeLicenseActionResponse.getApplicationDetails().add(applicationDetail);
		});
		
		return tradeLicenseActionResponse;
	}

	private ApplicationDetail getApplicationBillUserDetail(PetRegistrationApplication petRegistrationApplication,
			RequestInfo requestInfo) {
		
		ApplicationDetail applicationDetail = ApplicationDetail.builder()
				.applicationNumber(petRegistrationApplication.getApplicationNumber())
				.build();

		// total fee for NewTL
		BigDecimal totalFee = BigDecimal.valueOf(500.00);
		applicationDetail.setTotalPayableAmount(totalFee);
		

		// formula for NewTL
		StringBuilder feeCalculationFormula = new StringBuilder("Breed: "+petRegistrationApplication.getPetDetails().getBreedType());
		
		applicationDetail.setFeeCalculationFormula(feeCalculationFormula.toString());
		

		// search bill Details
		BillSearchCriteria billSearchCriteria = BillSearchCriteria.builder()
				.tenantId(petRegistrationApplication.getTenantId())
				.consumerCode(Collections.singleton(applicationDetail.getApplicationNumber()))
				.service("pet-services")
				.build();
		List<Bill> bills = billingService.searchBill(billSearchCriteria,requestInfo);
		Map<Object, Object> billDetailsMap = new HashMap<>();
		if (!CollectionUtils.isEmpty(bills)) {
			billDetailsMap.put("billId", bills.get(0).getId());
			applicationDetail.setTotalPayableAmount(bills.get(0).getTotalAmount());
		}
		applicationDetail.setBillDetails(billDetailsMap);
		
		// enrich userDetails
		Map<Object, Object> userDetails = new HashMap<>();
		userDetails.put("UserName", petRegistrationApplication.getApplicantName());
		userDetails.put("MobileNo", petRegistrationApplication.getMobileNumber());
		userDetails.put("Email", petRegistrationApplication.getEmailId());
		userDetails.put("Address", new String(petRegistrationApplication.getAddress().getAddressLine1().concat(", "))
//									.concat(petRegistrationApplication.getAddress().getAddressLine2().concat(", "))
//									.concat(petRegistrationApplication.getAddress().getDoorNo().concat(", "))
//									.concat(petRegistrationApplication.getAddress().getBuildingName().concat(", "))
//									.concat(petRegistrationApplication.getAddress().getStreet().concat(", "))
//									.concat(petRegistrationApplication.getAddress().getLandmark().concat(", "))
//									.concat(petRegistrationApplication.getAddress().getCity().concat(", "))
									.concat(petRegistrationApplication.getAddress().getPincode()));

		applicationDetail.setUserDetails(userDetails);
		
		
		
		return applicationDetail;
	}

	public PetRegistrationActionResponse getActionsOnApplication(
			PetRegistrationActionRequest ptradeLicenseActionRequest) {
		
		if(CollectionUtils.isEmpty(ptradeLicenseActionRequest.getApplicationNumbers())) {
			throw new CustomException("INVALID REQUEST","Provide Application Number.");
		}
		
		Map<String, List<String>> applicationActionMaps = new HashMap<>();
		
		ptradeLicenseActionRequest.getApplicationNumbers().stream().forEach(applicationNumber -> {
			
			// search application number
			PetApplicationSearchCriteria criteria = PetApplicationSearchCriteria.builder()
					.applicationNumber(applicationNumber)
					.build();
			List<PetRegistrationApplication> applications = petRegistrationRepository.getApplications(criteria);
			if(CollectionUtils.isEmpty(applications)) {
				throw new CustomException("APPLICATION_NOT_FOUND","No Application found with provided input.");
			}
			
			PetRegistrationApplication application = null != applications ? applications.get(0): null;
			
			String applicationStatus = application.getStatus();
			String applicationTenantId = application.getTenantId();
			String applicationBusinessId = "PTR";
			List<String> rolesWithinTenant = getRolesWithinTenant(applicationTenantId, ptradeLicenseActionRequest.getRequestInfo().getUserInfo().getRoles());
			
			
			// fetch business service search
			StringBuilder uri = new StringBuilder(config.getWfHost());
			uri.append(config.getWfBusinessServiceSearchPath());
			uri.append("?tenantId=").append(applicationTenantId);
			uri.append("&businessServices=").append(applicationBusinessId);
			RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder()
					.requestInfo(ptradeLicenseActionRequest.getRequestInfo()).build();
			
			Optional<Object> responseObject = serviceRequestRepository.fetchResult(uri, requestInfoWrapper);
			if(!responseObject.isPresent()) {
				throw new CustomException("WF_SEARCH_FAILED","Failed to fetch WF business service.");
			}
			LinkedHashMap<String, Object> responseMap = (LinkedHashMap<String, Object>) serviceRequestRepository.fetchResult(uri, requestInfoWrapper).get();
			BusinessServiceResponse businessServiceResponse = mapper.convertValue(responseMap
																					, BusinessServiceResponse.class);
			
			if(null == businessServiceResponse || CollectionUtils.isEmpty(businessServiceResponse.getBusinessServices())) {
				throw new CustomException("NO_BUSINESS_SERVICE_FOUND","Business service not found for application number: "+applicationNumber);
			}
			List<State> stateList = businessServiceResponse.getBusinessServices().get(0).getStates().stream()
					.filter(state -> StringUtils.equalsIgnoreCase(state.getApplicationStatus(), applicationStatus)
										&& !StringUtils.equalsAnyIgnoreCase(state.getApplicationStatus(), PTRConstants.APPLICATION_STATUS_APPROVED)).collect(Collectors.toList());
			
			// filtering actions based on roles
			List<String> actions = new ArrayList<>();
			stateList.stream().forEach(state -> {
				state.getActions().stream()
				.filter(action -> action.getRoles().stream().anyMatch(role -> rolesWithinTenant.contains(role)))
				.forEach(action -> {
					actions.add(action.getAction());
				});
			}) ;
			
			
			applicationActionMaps.put(applicationNumber, actions);
		});
		
		List<ApplicationDetail> nextActionList = new ArrayList<>();
		applicationActionMaps.entrySet().stream().forEach(entry -> {
			nextActionList.add(ApplicationDetail.builder().applicationNumber(entry.getKey()).action(entry.getValue()).build());
		});
		
		PetRegistrationActionResponse tradeLicenseActionResponse = PetRegistrationActionResponse.builder().applicationDetails(nextActionList).build();
		return tradeLicenseActionResponse;
	
	}

	private List<String> getRolesWithinTenant(String applicationTenantId, List<Role> roles) {

		List<String> roleCodes = roles.stream()
				.filter(role -> StringUtils.equalsIgnoreCase(role.getTenantId(), applicationTenantId)).map(role -> role.getCode())
				.collect(Collectors.toList());
		return roleCodes;
	}

}
