package com.mail.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 单进程托管前端 SPA：构建产物置于 classpath:/static，由本应用直接服务。
 * 对 HTML5 history 路由的深链（如 /mail、非 /api 前缀且无扩展名的路径）回退到 index.html，
 * 使刷新/直达不返回 404；REST 与静态资源命中时按原文件返回。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // API / WebSocket / actuator 交给各自处理器，不返回 index.html
                        if (resourcePath.startsWith("api/")
                                || resourcePath.startsWith("ws/")
                                || resourcePath.startsWith("actuator/")) {
                            return null;
                        }
                        // 其余视为前端路由：回退到打包进去的 index.html
                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
