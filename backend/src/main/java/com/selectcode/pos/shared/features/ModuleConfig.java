package com.selectcode.pos.shared.features;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Enforces {@link RequiresModule} on controller methods and controllers. */
@Configuration(proxyBeanMethods = false)
class ModuleConfig implements WebMvcConfigurer {

	private final ObjectProvider<ShopFeatures> features;

	ModuleConfig(ObjectProvider<ShopFeatures> features) {
		this.features = features;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new HandlerInterceptor() {
			@Override
			public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
				if (!(handler instanceof HandlerMethod method)) {
					return true;
				}
				RequiresModule required = method.getMethodAnnotation(RequiresModule.class);
				if (required == null) {
					required = method.getBeanType().getAnnotation(RequiresModule.class);
				}
				if (required != null) {
					features.getObject().require(required.value());
				}
				return true;
			}
		});
	}
}
