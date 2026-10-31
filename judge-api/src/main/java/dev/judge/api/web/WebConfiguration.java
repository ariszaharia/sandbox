package dev.judge.api.web;

import dev.judge.api.config.JudgeProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    private final JudgeProperties properties;

    WebConfiguration(JudgeProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminKeyInterceptor(properties.adminKey())).addPathPatterns("/api/problems", "/api/problems/**");
    }

    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> submissionBodyLimit() {
        FilterRegistrationBean<BodySizeLimitFilter> registration =
                new FilterRegistrationBean<>(new BodySizeLimitFilter(properties.maxSubmissionBody()));
        registration.addUrlPatterns("/api/submissions", "/api/submissions/*");
        return registration;
    }

    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> problemBodyLimit() {
        FilterRegistrationBean<BodySizeLimitFilter> registration =
                new FilterRegistrationBean<>(new BodySizeLimitFilter(properties.maxProblemBody()));
        registration.addUrlPatterns("/api/problems", "/api/problems/*");
        return registration;
    }
}
