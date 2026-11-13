package com.selectcode.pos.iam.infra;

import com.selectcode.pos.iam.ShopUserPrincipal;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/** Ends a user's server-side sessions (deactivation, password change/reset, shop suspension). */
@Component
public class SessionRevoker {

	private final FindByIndexNameSessionRepository<? extends Session> sessions;

	SessionRevoker(FindByIndexNameSessionRepository<? extends Session> sessions) {
		this.sessions = sessions;
	}

	/** @param keepSessionId a session to keep (the caller's own), or null */
	public int revoke(long shopId, long userId, String keepSessionId) {
		var found = sessions.findByPrincipalName(ShopUserPrincipal.sessionName(shopId, userId));
		int count = 0;
		for (String id : found.keySet()) {
			if (!id.equals(keepSessionId)) {
				sessions.deleteById(id);
				count++;
			}
		}
		return count;
	}
}
