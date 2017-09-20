/*
 * Copyright 2016 ELIXIR EBI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.elixir.ega.ebi.dataedge.config;

import com.google.common.cache.CacheBuilder;
import eu.elixir.ega.ebi.dataedge.dto.MyExternalConfig;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.guava.GuavaCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.AsyncRestTemplate;
import org.springframework.web.client.RestTemplate;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

/**
 *
 * @author asenf
 */
@Configuration
@EnableCaching
public class MyConfiguration { 
    @Value("${ega.ega.external.url}") String externalUrl;
    @Value("${ega.ega.cram.fasta}") String cramFastaReference;

    // Ribbon Load Balanced Rest Template for communication with other Microservices
    
    @Bean
    @LoadBalanced
    RestTemplate restTemplate() {
        return new RestTemplate();
    }
    
    @Bean
    @LoadBalanced
    AsyncRestTemplate asyncRestTemplate() {
        return new AsyncRestTemplate();
    }

    @Bean
    public Docket swaggerSettings() {
        return new Docket(DocumentationType.SWAGGER_2)
                .select()
                .apis(RequestHandlerSelectors.any())
                .paths(PathSelectors.any())
                .build()
                .pathMapping("/");
    }    

    //@Bean
    //public CacheManager cacheManager() {
    //    return new ConcurrentMapCacheManager("tokens");
    //}    

    //@Bean
    //public CacheManager concurrentCacheManager() {
    //
    //        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager();
    //        manager.setCacheNames(Arrays.asList("tokens", "reqFile", "index", "headerFile", "fileSize"));
    //
    //        return manager;
    //}

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager simpleCacheManager = new SimpleCacheManager();
        GuavaCache tokens = new GuavaCache("tokens", CacheBuilder.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .build());
        GuavaCache reqFile = new GuavaCache("reqFile", CacheBuilder.newBuilder()
                .expireAfterAccess(24, TimeUnit.HOURS)
                .build());
        GuavaCache index = new GuavaCache("index", CacheBuilder.newBuilder()
                .expireAfterAccess(24, TimeUnit.HOURS)
                .build());
        GuavaCache headerFile = new GuavaCache("headerFile", CacheBuilder.newBuilder()
                .expireAfterAccess(24, TimeUnit.HOURS)
                .build());
        GuavaCache fileSize = new GuavaCache("fileSize", CacheBuilder.newBuilder()
                .expireAfterAccess(24, TimeUnit.HOURS)
                .build());
        
        simpleCacheManager.setCaches(Arrays.asList(tokens, reqFile, index, 
                    headerFile, fileSize));
        return simpleCacheManager;
    }
    
    @Bean
    public MyExternalConfig MyArchiveConfig() {
        return new MyExternalConfig(externalUrl, cramFastaReference);
    }
}