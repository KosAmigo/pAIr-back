package com.pair.domain.company.controller;

import com.pair.domain.company.dto.InviteAcceptRequest;
import com.pair.domain.company.dto.InviteInfoResponse;
import com.pair.domain.company.service.CompanyInviteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/company-invites")
@Validated
public class CompanyInviteController {

    private final CompanyInviteService invites;

    public CompanyInviteController(CompanyInviteService invites) {
        this.invites = invites;
    }

    @GetMapping("/{token}")
    public ResponseEntity<InviteInfoResponse> inspect(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invites.inspect(token));
    }

    @PostMapping("/{token}/email-verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestVerification(@PathVariable String token) {
        invites.requestSignupCode(token);
    }

    @PostMapping("/accept")
    public ResponseEntity<com.pair.domain.auth.dto.AuthResponse> accept(@Valid @RequestBody InviteAcceptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(invites.accept(request));
    }
}
