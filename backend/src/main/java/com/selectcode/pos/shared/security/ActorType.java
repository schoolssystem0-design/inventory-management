package com.selectcode.pos.shared.security;

/** Who performed an action: a shop user, a platform user (SelectCode staff), or the system itself. */
public enum ActorType {
	USER, PLATFORM, SYSTEM
}
