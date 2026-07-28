package com.screenpilot.signage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.ResourceRegionHttpMessageConverter;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Small Spring MVC tweaks that plain defaults don't cover: partial-content responses for
 * video seeking, and correct URL generation when the app runs behind a reverse proxy or
 * HTTPS tunnel. Implements {@code WebMvcConfigurer} to hook into MVC setup without
 * replacing it.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** Required for HTTP Range (video seeking) responses returning ResourceRegion. */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(new ResourceRegionHttpMessageConverter());
    }

    /** Honor X-Forwarded-Proto/Host from tunnels and reverse proxies (HTTPS deployments). */
    @Bean
    public ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }
}
