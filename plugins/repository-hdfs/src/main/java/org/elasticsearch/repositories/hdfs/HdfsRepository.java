/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.repositories.hdfs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation.AuthenticationMethod;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.SpecialPermission;
import org.elasticsearch.cluster.metadata.RepositoryMetaData;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.blobstore.BlobPath;
import org.elasticsearch.common.blobstore.BlobStore;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.repositories.blobstore.BlobStoreRepository;

public final class HdfsRepository extends BlobStoreRepository {

    private static final Logger LOGGER = Loggers.getLogger(HdfsRepository.class);

    private static final String CONF_SECURITY_PRINCIPAL = "security.principal";

    private final Environment environment;
    private final ByteSizeValue chunkSize;
    private final boolean compress;
    private final BlobPath basePath = BlobPath.cleanPath();

    private HdfsBlobStore blobStore;

    // buffer size passed to HDFS read/write methods
    // TODO: why 100KB?
    private static final ByteSizeValue DEFAULT_BUFFER_SIZE = new ByteSizeValue(100, ByteSizeUnit.KB);

    public HdfsRepository(RepositoryMetaData metadata, Environment environment,
                          NamedXContentRegistry namedXContentRegistry) throws IOException {
        super(metadata, environment.settings(), namedXContentRegistry);

        this.environment = environment;
        this.chunkSize = metadata.settings().getAsBytesSize("chunk_size", null);
        this.compress = metadata.settings().getAsBoolean("compress", false);
    }

    @Override
    protected void doStart() {
        String uriSetting = getMetadata().settings().get("uri");
        if (Strings.hasText(uriSetting) == false) {
            throw new IllegalArgumentException("No 'uri' defined for hdfs snapshot/restore");
        }
        URI uri = URI.create(uriSetting);
        if ("hdfs".equalsIgnoreCase(uri.getScheme()) == false) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, "Invalid scheme [%s] specified in uri [%s]; only 'hdfs' uri allowed for hdfs snapshot/restore", uri.getScheme(), uriSetting));
        }
        if (Strings.hasLength(uri.getPath()) && uri.getPath().equals("/") == false) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Use 'path' option to specify a path [%s], not the uri [%s] for hdfs snapshot/restore", uri.getPath(), uriSetting));
        }

        String pathSetting = getMetadata().settings().get("path");
        // get configuration
        if (pathSetting == null) {
            throw new IllegalArgumentException("No 'path' defined for hdfs snapshot/restore");
        }

        int bufferSize = getMetadata().settings().getAsBytesSize("buffer_size", DEFAULT_BUFFER_SIZE).bytesAsInt();

        try {
            // initialize our filecontext
            SpecialPermission.check();
            FileContext fileContext = AccessController.doPrivileged((PrivilegedAction<FileContext>)
                () -> createContext(uri, getMetadata().settings()));
            blobStore = new HdfsBlobStore(fileContext, pathSetting, bufferSize);
            logger.debug("Using file-system [{}] for URI [{}], path [{}]", fileContext.getDefaultFileSystem(), fileContext.getDefaultFileSystem().getUri(), pathSetting);
        } catch (IOException e) {
            throw new UncheckedIOException(String.format(Locale.ROOT, "Cannot create HDFS repository for uri [%s]", uri), e);
        }
        super.doStart();
    }

    // create hadoop filecontext
    private FileContext createContext(URI uri, Settings repositorySettings)  {
        Configuration hadoopConfiguration = new Configuration(repositorySettings.getAsBoolean("load_defaults", true));
        hadoopConfiguration.setClassLoader(HdfsRepository.class.getClassLoader());
        hadoopConfiguration.reloadConfiguration();

        Map<String, String> map = repositorySettings.getByPrefix("conf.").getAsMap();
        for (Entry<String, String> entry : map.entrySet()) {
            hadoopConfiguration.set(entry.getKey(), entry.getValue());
        }

        // Create a hadoop user
        UserGroupInformation ugi = login(hadoopConfiguration, repositorySettings);

        // Disable FS cache
        hadoopConfiguration.setBoolean("fs.hdfs.impl.disable.cache", true);

        // Create the filecontext with our user information
        // This will correctly configure the filecontext to have our UGI as its internal user.
        return ugi.doAs((PrivilegedAction<FileContext>) () -> {
            try {
                AbstractFileSystem fs = AbstractFileSystem.get(uri, hadoopConfiguration);
                return FileContext.getFileContext(fs, hadoopConfiguration);
            } catch (UnsupportedFileSystemException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private UserGroupInformation login(Configuration hadoopConfiguration, Settings repositorySettings) {
        // Validate the authentication method:
        AuthenticationMethod authMethod = SecurityUtil.getAuthenticationMethod(hadoopConfiguration);
        if (authMethod.equals(AuthenticationMethod.SIMPLE) == false
            && authMethod.equals(AuthenticationMethod.KERBEROS) == false) {
            throw new RuntimeException("Unsupported authorization mode ["+authMethod+"]");
        }

        // Check if the user added a principal to use, and that there is a keytab file provided
        String kerberosPrincipal = repositorySettings.get(CONF_SECURITY_PRINCIPAL);

        // Check to see if the authentication method is compatible
        if (kerberosPrincipal != null && authMethod.equals(AuthenticationMethod.SIMPLE)) {
            LOGGER.warn("Hadoop authentication method is set to [SIMPLE], but a Kerberos principal is " +
                "specified. Continuing with [KERBEROS] authentication.");
            SecurityUtil.setAuthenticationMethod(AuthenticationMethod.KERBEROS, hadoopConfiguration);
        } else if (kerberosPrincipal == null && authMethod.equals(AuthenticationMethod.KERBEROS)) {
            throw new RuntimeException("HDFS Repository does not support [KERBEROS] authentication without " +
                "a valid Kerberos principal and keytab. Please specify a principal in the repository settings with [" +
                CONF_SECURITY_PRINCIPAL + "].");
        }

        // Now we can initialize the UGI with the configuration.
        UserGroupInformation.setConfiguration(hadoopConfiguration);

        // Debugging
        LOGGER.debug("Hadoop security enabled: [{}]", UserGroupInformation.isSecurityEnabled());
        LOGGER.debug("Using Hadoop authentication method: [{}]", SecurityUtil.getAuthenticationMethod(hadoopConfiguration));

        // UserGroupInformation (UGI) instance is just a Hadoop specific wrapper around a Java Subject
        try {
            if (UserGroupInformation.isSecurityEnabled()) {
                String principal = preparePrincipal(kerberosPrincipal);
                String keytab = HdfsSecurityContext.locateKeytabFile(environment).toString();
                LOGGER.debug("Using kerberos principal [{}] and keytab located at [{}]", principal, keytab);
                return UserGroupInformation.loginUserFromKeytabAndReturnUGI(principal, keytab);
            }
            return UserGroupInformation.getCurrentUser();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not retrieve the current user information", e);
        }
    }

    // Convert principals of the format 'service/_HOST@REALM' by subbing in the local address for '_HOST'.
    private static String preparePrincipal(String originalPrincipal) {
        String finalPrincipal = originalPrincipal;
        // Don't worry about host name resolution if they don't have the _HOST pattern in the name.
        if (originalPrincipal.contains("_HOST")) {
            try {
                finalPrincipal = SecurityUtil.getServerPrincipal(originalPrincipal, getHostName());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            if (originalPrincipal.equals(finalPrincipal) == false) {
                LOGGER.debug("Found service principal. Converted original principal name [{}] to server principal [{}]",
                    originalPrincipal, finalPrincipal);
            }
        }
        return finalPrincipal;
    }

    @SuppressForbidden(reason = "InetAddress.getLocalHost(); Needed for filling in hostname for a kerberos principal name pattern.")
    private static String getHostName() {
        try {
            /*
             * This should not block since it should already be resolved via Log4J and Netty. The
             * host information is cached by the JVM and the TTL for the cache entry is infinite
             * when the SecurityManager is activated.
             */
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            throw new RuntimeException("Could not locate host information", e);
        }
    }

    @Override
    protected BlobStore blobStore() {
        return blobStore;
    }

    @Override
    protected BlobPath basePath() {
        return basePath;
    }

    @Override
    protected boolean isCompress() {
        return compress;
    }

    @Override
    protected ByteSizeValue chunkSize() {
        return chunkSize;
    }
}
