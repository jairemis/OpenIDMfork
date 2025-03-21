/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Portions copyright 2013-2015 ForgeRock AS.
 * Portions Copyrighted 2024 3A Systems LLC.
 */
package org.forgerock.openidm.ui.internal.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.List;
import java.util.Dictionary;
import java.util.Hashtable;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.forgerock.json.JsonValue;
import org.forgerock.openidm.config.enhanced.EnhancedConfig;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.openidm.core.PropertyUtil;
import org.ops4j.pax.web.service.WebContainer;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet to handle the REST interface
 *
 * Based on apache felix org/apache/felix/http/base/internal/service/ResourceServlet.java
 *
 * Changes and additions by
 */
@Component(
        name = "org.forgerock.openidm.ui.context",
        immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE)
public final class ResourceServlet extends HttpServlet {
    private static final long serialVersionUID = 1;

    final static Logger logger = LoggerFactory.getLogger(ResourceServlet.class);

    /** config parameter keys */
    private static final String CONFIG_ENABLED = "enabled";
    private static final String CONFIG_CONTEXT_ROOT = "urlContextRoot";
    private static final String CONFIG_DEFAULT_DIR = "defaultDir";
    private static final String CONFIG_EXTENSION_DIR = "extensionDir";

    /** the Felix web console self-attaches to this servlet target */
    private static final String FELIX_WEB_CONSOLE = "/system/console";

    //TODO Decide where to put the web and the java resources. Now both are in root
    private String defaultDir;
    private String extensionDir;
    private String contextRoot;

    @Reference
    private WebContainer webContainer;

    /**vn comEnhanced configuration service. */
    @Reference(policy = ReferencePolicy.DYNAMIC)
    private volatile EnhancedConfig enhancedConfig;

    @Activate
    protected void activate(ComponentContext context) throws ServletException, NamespaceException {
        logger.info("Activating resource servlet with configuration {}", context.getProperties());
        init(context);
    }
    
    @Modified
    protected void modified(ComponentContext context) throws ServletException, NamespaceException {
        logger.info("Modifying resource servlet with configuration {}", context.getProperties());
        clear();
        init(context);
    }
    
    @Deactivate
    protected void deactivate(ComponentContext context) {
        logger.info("Deactivating resource servlet with configuration {}", context.getProperties());
        clear();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        logger.debug("GET call on {}", req);

        // the request pathInfo is always null for root contexts
        String target = ("/".equals(contextRoot))
                ? req.getServletPath()
                : req.getPathInfo();
        if (target == null || "".equals(target)) {
            res.sendRedirect(req.getServletPath() + "/");
        } else {
            if ("/".equals(target)) {
                target = "/index.html";
            }

            // Never cache index.html to ensure we always have the current product version for asset requests
            if (target.equals("/index.html")) {
                res.setHeader("Cache-Control", "no-cache");
            }

            target = prependSlash(target);
            if (target.startsWith(FELIX_WEB_CONSOLE)) {
                // this request is not for us
                return;
            }

            // Locate the file in extension dir first, fall back to default dir
            URL url = null;
            String loadDir = (String) PropertyUtil.substVars(extensionDir, IdentityServer.getInstance(), false);
            File file = new File(loadDir + target);
            if (isValidFile(file, loadDir)) {
                url = file.getCanonicalFile().toURI().toURL();
            } else {
                loadDir = (String) PropertyUtil.substVars(defaultDir, IdentityServer.getInstance(), false);
                file = new File(loadDir + target);
                if (isValidFile(file, loadDir)) {
                    url = file.getCanonicalFile().toURI().toURL();
                }
            }

            // Validate the constructed URL against the allowed list
            if (url != null && !isAllowedURL(url)) {
                res.sendError(HttpServletResponse.SC_FORBIDDEN, "Access to the requested resource is forbidden.");
                return;
            }

            if (url == null) {
                res.sendError(HttpServletResponse.SC_NOT_FOUND);
            } else {
                handle(req, res, url, target);
            }
        }
    }

    /**
     * Initializes the servlet and registers it with the WebContainer.
     * 
     * @param context the ComponentContext containing the configuration
     * @throws ServletException
     * @throws NamespaceException
     */
    private void init(ComponentContext context) throws ServletException, NamespaceException {
        JsonValue config = enhancedConfig.getConfigurationAsJson(context);
        
        if (!config.get(CONFIG_ENABLED).isNull() && Boolean.FALSE.equals(config.get(CONFIG_ENABLED).asBoolean())) {
            logger.info("UI is disabled - not registering UI servlet");
            return;
        } else if (config.get(CONFIG_CONTEXT_ROOT) == null || config.get(CONFIG_CONTEXT_ROOT).isNull()) {
            logger.info("UI does not specify contextRoot - unable to register servlet");
            return;
        } else if (config.get(CONFIG_DEFAULT_DIR) == null
                || config.get(CONFIG_DEFAULT_DIR).isNull()) {
            logger.info("UI does not specify default directory - unable to register servlet");
            return;
        } else if (config.get(CONFIG_EXTENSION_DIR) == null
                || config.get(CONFIG_EXTENSION_DIR).isNull()) {
            logger.info("UI does not specify extension directory - unable to register servlet");
            return;
        }

        defaultDir = config.get(CONFIG_DEFAULT_DIR).asString();
        extensionDir = config.get(CONFIG_EXTENSION_DIR).asString();
        contextRoot = prependSlash(config.get(CONFIG_CONTEXT_ROOT).asString());

        Dictionary<String, Object> props = new Hashtable<>();
        webContainer.registerServlet(contextRoot, this,  props, webContainer.getDefaultSharedHttpContext());
        logger.debug("Registered UI servlet at {}", contextRoot);
    }
    
    /**
     * Clears the servlet, unregistering it with the WebContainer and removing the bundle listener.
     */
    private void clear() {
        webContainer.unregister(contextRoot);
        logger.debug("Unregistered UI servlet at {}", contextRoot);
    }
    
    private void handle(HttpServletRequest req, HttpServletResponse res, URL url, String resName)
            throws IOException {
        String contentType = getServletContext().getMimeType(resName);
        if (contentType != null) {
            res.setContentType(contentType);
        } else {
            res.setContentType(getMimeType(resName));
        }

        long lastModified = getLastModified(url);
        if (lastModified != 0) {
            res.setDateHeader("Last-Modified", lastModified);
        }

        if (!resourceModified(lastModified, req.getDateHeader("If-Modified-Since"))) {
            res.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
        } else {
            copyResource(url, res);
        }
    }

    private long getLastModified(URL url) {
        long lastModified = 0;

        try {
            URLConnection conn = url.openConnection();
            lastModified = conn.getLastModified();
        } catch (Exception e) {
            // Do nothing
        }

        if (lastModified == 0) {
            String filepath = url.getPath();
            if (filepath != null) {
                File f = new File(filepath);
                if (f.exists()) {
                    lastModified = f.lastModified();
                }
            }
        }

        return lastModified;
    }
    
    private String getMimeType(String fileName) {
        if (fileName.endsWith(".css")) {
            return "text/css";
        } else if (fileName.endsWith(".js")) {
            return "application/javascript";
        } else if (fileName.endsWith(".png")) {
            return "image/png";
        } else if (fileName.endsWith(".html")) {
            return "text/html";
        }
        
        return null;
    }

    private boolean resourceModified(long resTimestamp, long modSince) {
        modSince /= 1000;
        resTimestamp /= 1000;

        return resTimestamp == 0 || modSince == -1 || resTimestamp > modSince;
    }

    private void copyResource(URL url, HttpServletResponse res)
            throws IOException {
        OutputStream os = null;
        InputStream is = null;

        try {
            os = res.getOutputStream();
            is = url.openStream();

            int len = 0;
            byte[] buf = new byte[1024];
            int n;

            while ((n = is.read(buf, 0, buf.length)) >= 0) {
                os.write(buf, 0, n);
                len += n;
            }

            res.setContentLength(len);
        } finally {
            if (is != null) {
                is.close();
            }

            if (os != null) {
                os.close();
            }
        }
    }

    private String prependSlash(String path) {
        return path.startsWith("/") ? path : "/" + path;
    }

    private boolean isValidFile(File file, String loadDir) throws IOException {
        return file.getCanonicalPath().startsWith(new File(loadDir).getCanonicalPath())
                && file.exists() && !file.isDirectory();
    }

    private static final List<String> ALLOWED_DIRECTORIES = Arrays.asList(
            "/allowed/dir1",
            "/allowed/dir2"
    );

    private boolean isAllowedURL(URL url) throws IOException {
        String canonicalPath = new File(url.getPath()).getCanonicalPath();
        for (String allowedDir : ALLOWED_DIRECTORIES) {
            if (canonicalPath.startsWith(new File(allowedDir).getCanonicalPath())) {
                return true;
            }
        }
        return false;
    }
}
