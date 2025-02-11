/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch;

import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.Booleans;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.internal.BuildExtension;

import java.io.IOException;
import java.net.URL;
import java.security.CodeSource;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Information about a build of Elasticsearch.
 */
public record Build(
    String flavor,
    Type type,
    String hash,
    String date,
    boolean isSnapshot,
    String version,
    String minWireCompatVersion,
    String minIndexCompatVersion,
    String displayString
) {

    private static class CurrentHolder {
        private static final Build CURRENT = findCurrent();

        // finds the pluggable current build, or uses the local build as a fallback
        private static Build findCurrent() {
            var buildExtension = BuildExtension.load();
            if (buildExtension == null) {
                return findLocalBuild();
            }
            var build = buildExtension.getCurrentBuild();
            return build;
        }
    }

    /**
     * Finds build info scanned from the server jar.
     */
    private static Build findLocalBuild() {
        final Type type;
        final String hash;
        final String date;
        final boolean isSnapshot;
        final String version;

        // these are parsed at startup, and we require that we are able to recognize the values passed in by the startup scripts
        type = Type.fromDisplayName(System.getProperty("es.distribution.type", "unknown"), true);

        final String esPrefix = "elasticsearch-" + Version.CURRENT;
        final URL url = getElasticsearchCodeSourceLocation();
        final String urlStr = url == null ? "" : url.toString();
        if (urlStr.startsWith("file:/")
            && (urlStr.endsWith(esPrefix + ".jar") || urlStr.matches("(.*)" + esPrefix + "(-)?((alpha|beta|rc)[0-9]+)?(-SNAPSHOT)?.jar"))) {
            try (JarInputStream jar = new JarInputStream(FileSystemUtils.openFileURLStream(url))) {
                Manifest manifest = jar.getManifest();
                hash = manifest.getMainAttributes().getValue("Change");
                date = manifest.getMainAttributes().getValue("Build-Date");
                isSnapshot = "true".equals(manifest.getMainAttributes().getValue("X-Compile-Elasticsearch-Snapshot"));
                version = manifest.getMainAttributes().getValue("X-Compile-Elasticsearch-Version");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            // not running from the official elasticsearch jar file (unit tests, IDE, uber client jar, shadiness)
            hash = "unknown";
            date = "unknown";
            version = Version.CURRENT.toString();
            final String buildSnapshot = System.getProperty("build.snapshot");
            if (buildSnapshot != null) {
                try {
                    Class.forName("com.carrotsearch.randomizedtesting.RandomizedContext");
                } catch (final ClassNotFoundException e) {
                    // we are not in tests but build.snapshot is set, bail hard
                    throw new IllegalStateException("build.snapshot set to [" + buildSnapshot + "] but not running tests");
                }
                isSnapshot = Booleans.parseBoolean(buildSnapshot);
            } else {
                isSnapshot = true;
            }
        }
        if (hash == null) {
            throw new IllegalStateException(
                "Error finding the build hash. "
                    + "Stopping Elasticsearch now so it doesn't run in subtly broken ways. This is likely a build bug."
            );
        }
        if (date == null) {
            throw new IllegalStateException(
                "Error finding the build date. "
                    + "Stopping Elasticsearch now so it doesn't run in subtly broken ways. This is likely a build bug."
            );
        }
        if (version == null) {
            throw new IllegalStateException(
                "Error finding the build version. "
                    + "Stopping Elasticsearch now so it doesn't run in subtly broken ways. This is likely a build bug."
            );
        }

        final String flavor = "default";
        String minWireCompat = Version.CURRENT.minimumCompatibilityVersion().toString();
        String minIndexCompat = minimumCompatString(IndexVersion.MINIMUM_COMPATIBLE);
        String displayString = defaultDisplayString(type, hash, date, version);

        return new Build(flavor, type, hash, date, isSnapshot, version, minWireCompat, minIndexCompat, displayString);
    }

    public static String minimumCompatString(IndexVersion minimumCompatible) {
        if (minimumCompatible.before(IndexVersion.V_8_11_0)) {
            // use Version for compatibility
            return Version.fromId(minimumCompatible.id()).toString();
        } else {
            // use the IndexVersion string
            return minimumCompatible.toString();
        }
    }

    public static Build current() {
        return CurrentHolder.CURRENT;
    }

    public enum Type {

        DEB("deb"),
        DOCKER("docker"),
        RPM("rpm"),
        TAR("tar"),
        ZIP("zip"),
        UNKNOWN("unknown");

        final String displayName;

        public String displayName() {
            return displayName;
        }

        Type(final String displayName) {
            this.displayName = displayName;
        }

        public static Type fromDisplayName(final String displayName, final boolean strict) {
            switch (displayName) {
                case "deb":
                    return Type.DEB;
                case "docker":
                    return Type.DOCKER;
                case "rpm":
                    return Type.RPM;
                case "tar":
                    return Type.TAR;
                case "zip":
                    return Type.ZIP;
                case "unknown":
                    return Type.UNKNOWN;
                default:
                    if (strict) {
                        throw new IllegalStateException("unexpected distribution type [" + displayName + "]; your distribution is broken");
                    } else {
                        return Type.UNKNOWN;
                    }
            }
        }
    }

    /**
     * The location of the code source for Elasticsearch
     *
     * @return the location of the code source for Elasticsearch which may be null
     */
    static URL getElasticsearchCodeSourceLocation() {
        final CodeSource codeSource = Build.class.getProtectionDomain().getCodeSource();
        return codeSource == null ? null : codeSource.getLocation();
    }

    public static Build readBuild(StreamInput in) throws IOException {
        final String flavor;
        if (in.getTransportVersion().before(TransportVersions.V_8_3_0)
            || in.getTransportVersion().onOrAfter(TransportVersions.V_8_500_039)) {
            flavor = in.readString();
        } else {
            flavor = "default";
        }
        // be lenient when reading on the wire, the enumeration values from other versions might be different than what we know
        final Type type = Type.fromDisplayName(in.readString(), false);
        String hash = in.readString();
        String date = in.readString();
        boolean snapshot = in.readBoolean();
        final String version = in.readString();
        final String minWireVersion;
        final String minIndexVersion;
        final String displayString;
        if (in.getTransportVersion().onOrAfter(TransportVersions.V_8_500_041)) {
            minWireVersion = in.readString();
            minIndexVersion = in.readString();
            displayString = in.readString();
        } else {
            // the version is qualified, so we may need to strip off -SNAPSHOT or -alpha, etc. Here we simply find the first dash
            int dashNdx = version.indexOf('-');
            var versionConstant = Version.fromString(dashNdx == -1 ? version : version.substring(0, dashNdx));
            minWireVersion = versionConstant.minimumCompatibilityVersion().toString();
            minIndexVersion = minimumCompatString(IndexVersion.getMinimumCompatibleIndexVersion(versionConstant.id()));
            displayString = defaultDisplayString(type, hash, date, version);
        }
        return new Build(flavor, type, hash, date, snapshot, version, minWireVersion, minIndexVersion, displayString);
    }

    public static void writeBuild(Build build, StreamOutput out) throws IOException {
        if (out.getTransportVersion().before(TransportVersions.V_8_3_0)
            || out.getTransportVersion().onOrAfter(TransportVersions.V_8_500_039)) {
            out.writeString(build.flavor());
        }
        out.writeString(build.type().displayName());
        out.writeString(build.hash());
        out.writeString(build.date());
        out.writeBoolean(build.isSnapshot());
        out.writeString(build.qualifiedVersion());
        if (out.getTransportVersion().onOrAfter(TransportVersions.V_8_500_041)) {
            out.writeString(build.minWireCompatVersion());
            out.writeString(build.minIndexCompatVersion());
            out.writeString(build.displayString());
        }
    }

    /**
     * Get the version as considered at build time
     * <p>
     * Offers a way to get the fully qualified version as configured by the build.
     * This will be the same as {@link Version} for production releases, but may include on of the qualifier ( e.x alpha1 )
     * or -SNAPSHOT for others.
     *
     * @return the fully qualified build
     */
    public String qualifiedVersion() {
        return version;
    }

    public boolean isProductionRelease() {
        return isSnapshot() == false && version.matches(".*(-alpha\\d+)|(-beta\\d+)|(-rc\\d+)") == false;
    }

    public static String defaultDisplayString(Type type, String hash, String date, String version) {
        return "[" + type.displayName + "][" + hash + "][" + date + "][" + version + "]";
    }

    @Override
    public String toString() {
        return displayString();
    }
}
