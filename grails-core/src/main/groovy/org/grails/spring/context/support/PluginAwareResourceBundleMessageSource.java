/*
 * Copyright 2004-2005 Graeme Rocher
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
package org.grails.spring.context.support;

import grails.core.GrailsApplication;
import grails.core.support.GrailsApplicationAware;
import grails.plugins.GrailsPlugin;
import grails.plugins.GrailsPluginManager;
import grails.plugins.PluginManagerAware;
import grails.util.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.grails.core.io.CachingPathMatchingResourcePatternResolver;
import org.grails.plugins.BinaryGrailsPlugin;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A ReloadableResourceBundleMessageSource that is capable of loading message sources from plugins.
 *
 * @author Graeme Rocher
 * @since 1.1
 */
public class PluginAwareResourceBundleMessageSource extends ReloadableResourceBundleMessageSource implements GrailsApplicationAware, PluginManagerAware, InitializingBean {
    private static final String GRAILS_APP_I18N_PATH_COMPONENT = "/grails-app/i18n/";
    protected GrailsApplication application;
    protected GrailsPluginManager pluginManager;
    protected List<String> pluginBaseNames = new ArrayList<String>();
    private ResourceLoader localResourceLoader;
    private PathMatchingResourcePatternResolver resourceResolver;
    private ConcurrentMap<Locale, CacheEntry<PropertiesHolder>> cachedMergedPluginProperties = new ConcurrentHashMap<Locale, CacheEntry<PropertiesHolder>>();
    private ConcurrentMap<Locale, CacheEntry<PropertiesHolder>> cachedMergedBinaryPluginProperties = new ConcurrentHashMap<Locale, CacheEntry<PropertiesHolder>>();
    private long pluginCacheMillis = Long.MIN_VALUE;

    @Deprecated
    public List<String> getPluginBaseNames() {
        return pluginBaseNames;
    }

    @Deprecated
    public void setPluginBaseNames(List<String> pluginBaseNames) {
        this.pluginBaseNames = pluginBaseNames;
    }

    public void setGrailsApplication(GrailsApplication grailsApplication) {
        application = grailsApplication;
    }

    public void setPluginManager(GrailsPluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    public void setResourceResolver(PathMatchingResourcePatternResolver resourceResolver) {
        this.resourceResolver = resourceResolver;
    }

    public void afterPropertiesSet() throws Exception {
        if (pluginCacheMillis == Long.MIN_VALUE) {
            pluginCacheMillis = cacheMillis;
        }
        
        if (localResourceLoader == null) {
            return;
        }

        Resource[] resources;
        if(Environment.isDevelopmentMode()) {
            File[] propertiesFiles = new File(BuildSettings.BASE_DIR, GRAILS_APP_I18N_PATH_COMPONENT).listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.endsWith(".properties");
                }
            });
            if(propertiesFiles != null && propertiesFiles.length > 0) {
                List<Resource> resourceList = new ArrayList<Resource>(propertiesFiles.length);
                for (File propertiesFile : propertiesFiles) {
                    resourceList.add(new FileSystemResource(propertiesFile));
                }
                resources = resourceList.toArray(new Resource[resourceList.size()]);
            }
            else {
                resources = new Resource[0];
            }
        }
        else {
            resources = resourceResolver.getResources("classpath*:**/*.properties");
        }

        List<String> basenames = new ArrayList<String>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            String baseName = GrailsStringUtils.getFileBasename(filename);
            int i = baseName.indexOf('_');
            if(i > -1) {
                baseName = baseName.substring(0, i);
            }
            if(!basenames.contains(baseName))
                basenames.add(baseName);
        }

        setBasenames(basenames.toArray( new String[basenames.size()]));

    }


    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        String msg = super.resolveCodeWithoutArguments(code, locale);
        return msg == null ? resolveCodeWithoutArgumentsFromPlugins(code, locale) : msg;
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        MessageFormat mf = super.resolveCode(code, locale);
        return mf == null ? resolveCodeFromPlugins(code, locale) : mf;
    }

    /**
     * Get a PropertiesHolder that contains the actually visible properties
     * for a Locale, after merging all specified resource bundles.
     * Either fetches the holder from the cache or freshly loads it.
     * <p>Only used when caching resource bundle contents forever, i.e.
     * with cacheSeconds < 0. Therefore, merged properties are always
     * cached forever.
     */
    protected PropertiesHolder getMergedPluginProperties(final Locale locale) {
        return CacheEntry.getValue(cachedMergedPluginProperties, locale, cacheMillis, new Callable<PropertiesHolder>() {
            @Override
            public PropertiesHolder call() throws Exception {
                Properties mergedProps = new Properties();
                PropertiesHolder mergedHolder = new PropertiesHolder(mergedProps);
                mergeBinaryPluginProperties(locale, mergedProps);
                return mergedHolder;
            }
        });
    }

    /**
     * Attempts to resolve a String for the code from the list of plugin base names
     *
     * @param code The code
     * @param locale The locale
     * @return a MessageFormat
     */
    protected String resolveCodeWithoutArgumentsFromPlugins(String code, Locale locale) {
        if (pluginCacheMillis < 0) {
            PropertiesHolder propHolder = getMergedPluginProperties(locale);
            String result = propHolder.getProperty(code);
            if (result != null) {
                return result;
            }
        }
        else {
            String result = findCodeInBinaryPlugins(code, locale);
            if (result != null) return result;

        }
        return null;
    }
    
    protected PropertiesHolder getMergedBinaryPluginProperties(final Locale locale) {
        return CacheEntry.getValue(cachedMergedBinaryPluginProperties, locale, cacheMillis, new Callable<PropertiesHolder>() {
            @Override
            public PropertiesHolder call() throws Exception {
                Properties mergedProps = new Properties();
                PropertiesHolder mergedHolder = new PropertiesHolder(mergedProps);
                mergeBinaryPluginProperties(locale, mergedProps);
                return mergedHolder;
            }

        });
    }

    protected void mergeBinaryPluginProperties(final Locale locale, Properties mergedProps) {
        final GrailsPlugin[] allPlugins = pluginManager.getAllPlugins();
        for (GrailsPlugin plugin : allPlugins) {
            if (plugin instanceof BinaryGrailsPlugin) {
                BinaryGrailsPlugin binaryPlugin = (BinaryGrailsPlugin) plugin;
                final Properties binaryPluginProperties = binaryPlugin.getProperties(locale);
                if (binaryPluginProperties != null) {
                    mergedProps.putAll(binaryPluginProperties);
                }
            }
        }
    }

    private String findCodeInBinaryPlugins(String code, Locale locale) {
        return getMergedBinaryPluginProperties(locale).getProperty(code);
    }

    private MessageFormat findMessageFormatInBinaryPlugins(String code, Locale locale) {
        return getMergedBinaryPluginProperties(locale).getMessageFormat(code, locale);
    }

    /**
     * Attempts to resolve a MessageFormat for the code from the list of plugin base names
     *
     * @param code The code
     * @param locale The locale
     * @return a MessageFormat
     */
    protected MessageFormat resolveCodeFromPlugins(String code, Locale locale) {
        if (pluginCacheMillis < 0) {
            PropertiesHolder propHolder = getMergedPluginProperties(locale);
            MessageFormat result = propHolder.getMessageFormat(code, locale);
            if (result != null) {
                return result;
            }
        }
        else {
            MessageFormat result = findMessageFormatInBinaryPlugins(code, locale);
            if (result != null) return result;
        }
        return null;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        super.setResourceLoader(resourceLoader);

        this.localResourceLoader = resourceLoader;
        if (resourceResolver == null) {
            resourceResolver = new CachingPathMatchingResourcePatternResolver(localResourceLoader);
        }
    }

    
    /**
     * Set the number of seconds to cache the list of matching properties files loaded from plugin.
     * <ul>
     * <li>Default value is the same value as cacheSeconds
     * </ul>
     */
    public void setPluginCacheSeconds(int pluginCacheSeconds) {
        this.pluginCacheMillis = (pluginCacheSeconds * 1000);
    }    
}
