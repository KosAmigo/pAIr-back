package com.pair.company;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pair.web.auth.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/console/company")
@Validated
public class CompanyConsoleController {

    private final CompanyMemberService members;
    private final CompanyInviteService invites;

    public CompanyConsoleController(CompanyMemberService members, CompanyInviteService invites) {
        this.members = members;
        this.invites = invites;
    }

    @GetMapping("/members")
    public CompanyMemberDtos.MemberList members(CurrentUser user) {
        return members.membersForManager(user);
    }

    @PostMapping("/proctor-invites")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CompanyInviteDtos.InviteSent inviteProctor(CurrentUser user, @Valid @RequestBody CompanyInviteDtos.InviteRequest request) {
        invites.inviteProctor(user, request.email());
        return new CompanyInviteDtos.InviteSent("감독관 초대 이메일을 발송했습니다.");
    }

    @PostMapping("/manager-delegation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delegate(CurrentUser user, @Valid @RequestBody CompanyMemberDtos.DelegateRequest request) {
        members.delegate(user, request.proctorUserId());
    }
}
