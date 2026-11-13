package com.selectcode.pos.shared.messaging;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * How messages leave the system. Local/test: an in-memory outbox (readable at GET /api/v1/dev/outbox when running
 * locally). Elsewhere: email by an HTTPS email API (MAIL_PROVIDER=brevo|resend + MAIL_API_KEY) or SMTP
 * (SPRING_MAIL_HOST...). SMS through the Android gateway arrives in Stage 3.
 */
@Configuration(proxyBeanMethods = false)
class MessagingConfig {

	private static final Logger log = LoggerFactory.getLogger(MessagingConfig.class);

	@Bean
	@Primary
	MessageSender messageSender(Environment environment, ObjectProvider<DevOutbox> devOutbox,
			ObjectProvider<JavaMailSender> mailSender, JsonMapper json) {
		if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
			return devOutbox.getObject();
		}
		String from = environment.getProperty("app.mail.from", "");
		String fromName = environment.getProperty("app.mail.from-name", "SelectCode POS");
		String provider = environment.getProperty("app.mail.provider", "").trim().toLowerCase();
		EmailTransport transport = null;
		if (("brevo".equals(provider) || "resend".equals(provider))
				&& StringUtils.hasText(environment.getProperty("app.mail.api-key"))) {
			transport = EmailTransport.Api.of(provider, null, environment.getProperty("app.mail.api-key"), from,
					fromName, json);
		}
		else if (mailSender.getIfAvailable() != null && StringUtils.hasText(environment.getProperty("spring.mail.host"))) {
			transport = new EmailTransport.Smtp(mailSender.getObject(), environment.getProperty("spring.mail.host"),
					environment.getProperty("spring.mail.port", Integer.class, 587),
					StringUtils.hasText(from) ? from : environment.getProperty("spring.mail.username", ""), fromName);
		}
		if (transport == null) {
			log.warn("No email provider configured: setup links and password resets will not be emailed");
		}
		else {
			log.info("Email through {}", transport.describe());
		}
		EmailTransport email = transport;
		return new MessageSender() {
			// Never throws: a mail server that is down must not undo the business change that sent the email.
			@Override
			public boolean send(OutgoingMessage message) {
				if (message.channel() == OutgoingMessage.Channel.EMAIL && email != null) {
					try {
						email.send(message.recipient(), message.subject(), message.body());
						return true;
					}
					catch (EmailTransport.EmailFailure failure) {
						log.warn("Email not sent: {}", failure.getMessage());
						return false;
					}
				}
				log.warn("No provider configured for {} messages; message not sent", message.channel());
				return false;
			}

			@Override
			public boolean supports(OutgoingMessage.Channel channel) {
				return channel == OutgoingMessage.Channel.EMAIL && email != null;
			}
		};
	}

	/** In-memory outbox for local development and tests. Never active in staging/production. */
	@Profile({ "local", "test" })
	@Bean
	DevOutbox devOutbox() {
		return new DevOutbox();
	}

	@Profile("local")
	@RestController
	static class DevOutboxController {

		private final DevOutbox outbox;

		DevOutboxController(DevOutbox outbox) {
			this.outbox = outbox;
		}

		/** Local development only: read emails the app would have sent (setup links, reset links). */
		@com.selectcode.pos.shared.security.PublicEndpoint
		@GetMapping("/api/v1/dev/outbox")
		List<DevOutbox.OutboxEntry> outbox() {
			return outbox.entries();
		}
	}
}
