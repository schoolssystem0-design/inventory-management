package com.selectcode.pos.shared.messaging;

/**
 * Sends email/SMS: account setup links, password resets and (Stage 3) receipts and reminders.
 */
public interface MessageSender {

	/** @return true if the message was handed to a provider */
	boolean send(OutgoingMessage message);

	boolean supports(OutgoingMessage.Channel channel);
}
