package cloide.server.module.auth.oidc;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.auth.mapper.UserMapper;
import cloide.server.module.auth.service.UserService;
import cloide.server.module.auth.vo.LoginVo;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OidcAccounts {
    private final JdbcTemplate jdbc;
    private final OidcSettings settings;
    private final UserMapper users;
    private final UserService sessions;
    private byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    @Transactional
    public void bind(long id,String subject,long admin) {
        if(!settings.enabled || subject==null || subject.isBlank() || bytes(subject).length>255)
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        var user=users.selectAuthenticationUserById(id);
        if(user==null || !"approved".equals(user.getReviewStatus()))throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        try {
            jdbc.update("DELETE FROM oidc_account_mappings WHERE user_id=?",id);
            jdbc.update("INSERT INTO oidc_account_mappings(user_id,issuer,subject_id,assigned_by) VALUES(?,?,?,?)",
                    id,bytes(settings.issuer),bytes(subject),admin);
        } catch(DuplicateKeyException conflict){throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);}
    }
    public void unbind(long id){jdbc.update("DELETE FROM oidc_account_mappings WHERE user_id=?",id);}
    @Transactional
    public LoginVo login(String subject) {
        long id=jdbc.query("SELECT user_id FROM oidc_account_mappings WHERE issuer=? AND subject_id=? FOR UPDATE",
                (rs,i)->rs.getLong(1),bytes(settings.issuer),bytes(subject)).stream().findFirst().orElseThrow(OidcState::denied);
        return sessions.loginVerifiedIdentity(id);
    }
}
