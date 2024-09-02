package org.egov.advertisementcanopy.controller;


import org.egov.advertisementcanopy.model.SiteCountRequest;
import org.egov.advertisementcanopy.model.SiteCountResponse;
import org.egov.advertisementcanopy.model.SiteCreationRequest;
import org.egov.advertisementcanopy.model.SiteCreationResponse;
import org.egov.advertisementcanopy.model.SiteSearchRequest;
import org.egov.advertisementcanopy.model.SiteSearchResponse;
import org.egov.advertisementcanopy.model.SiteUpdateRequest;
import org.egov.advertisementcanopy.model.SiteUpdationResponse;
import org.egov.advertisementcanopy.service.SiteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/site")
public class SiteController {
	
	@Autowired
	SiteService siteService;
	
	 @CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", allowCredentials = "true")
	    @PostMapping("/_create")
	    public ResponseEntity<SiteCreationResponse> create(@RequestBody SiteCreationRequest createSiteRequest) {
	        return ResponseEntity.ok(siteService.create(createSiteRequest));
	    }
	 
	 @CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", allowCredentials = "true")
	    @PostMapping("/_update")
	    public ResponseEntity<SiteUpdationResponse> update(@RequestBody SiteUpdateRequest updateSiteRequest) {
	        return ResponseEntity.ok(siteService.update(updateSiteRequest));
	    }
	 
	 @CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", allowCredentials = "true")
	    @PostMapping("/_search")
	    public ResponseEntity<SiteSearchResponse> search(@RequestBody SiteSearchRequest searchSiteRequest) {
	        return ResponseEntity.ok(siteService.search(searchSiteRequest));
	    }
	 @CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", allowCredentials = "true")
	    @PostMapping("/_count")
	    public ResponseEntity<SiteCountResponse> siteCount(@RequestBody SiteCountRequest siteCountRequest) {
	        return ResponseEntity.ok(siteService.totalCount(siteCountRequest));
	    }

}