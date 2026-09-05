import com.android.aapt.ConfigurationOuterClass.Configuration;
import com.android.aapt.Resources;
import com.android.bundle.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies the offline language contract of a release Android App Bundle.
 *
 * <p>This deliberately consumes the generated protobuf artifacts. A source
 * setting or a string search cannot prove what Play will receive.</p>
 */
public final class BundleLanguageVerifier {
    private static final String APPLICATION_PACKAGE = "jp.rimtty.codematch";

    private static final Map<String, ExpectedResource> REQUIRED_RESOURCES = Map.of(
            "scan_wait_qr_title",
            new ExpectedResource("QRコード読み取り", "Scan a QR code"),
            "settings_title",
            new ExpectedResource("設定", "Settings"),
            "history_title",
            new ExpectedResource("照合履歴", "Match history")
    );

    private BundleLanguageVerifier() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            fail("Usage: BundleLanguageVerifier <release.aab>");
        }

        try {
            verify(Path.of(args[0]));
            System.out.println("AAB language delivery verification passed: " + args[0]);
        } catch (Exception error) {
            System.err.println("AAB language delivery verification failed: " + error.getMessage());
            System.exit(1);
        }
    }

    public static void verify(Path bundlePath) throws IOException {
        if (bundlePath == null || !Files.isRegularFile(bundlePath)) {
            throw failure("AAB does not exist: " + bundlePath);
        }
        if (Files.size(bundlePath) == 0) {
            throw failure("AAB is empty: " + bundlePath);
        }

        try (ZipFile bundle = new ZipFile(bundlePath.toFile())) {
            Config.BundleConfig bundleConfig = parseBundleConfig(bundle);
            verifySplitConfig(bundleConfig);

            Resources.ResourceTable resourceTable = parseResourceTable(bundle);
            verifyRequiredResources(resourceTable);
        } catch (VerificationException error) {
            throw error;
        } catch (Exception error) {
            throw failure("Unable to inspect AAB " + bundlePath + ": " + error, error);
        }
    }

    private static Config.BundleConfig parseBundleConfig(ZipFile bundle) throws IOException {
        ZipEntry entry = requiredEntry(bundle, "BundleConfig.pb");
        try (InputStream input = bundle.getInputStream(entry)) {
            return Config.BundleConfig.parseFrom(input);
        } catch (Exception error) {
            throw failure("BundleConfig.pb is missing or malformed", error);
        }
    }

    private static Resources.ResourceTable parseResourceTable(ZipFile bundle) throws IOException {
        ZipEntry entry = requiredEntry(bundle, "base/resources.pb");
        try (InputStream input = bundle.getInputStream(entry)) {
            return Resources.ResourceTable.parseFrom(input);
        } catch (Exception error) {
            throw failure("base/resources.pb is missing or malformed", error);
        }
    }

    private static ZipEntry requiredEntry(ZipFile bundle, String name) throws IOException {
        ZipEntry matchingEntry = null;
        int matches = 0;
        var entries = bundle.entries();
        while (entries.hasMoreElements()) {
            ZipEntry candidate = entries.nextElement();
            if (name.equals(candidate.getName())) {
                matchingEntry = candidate;
                matches++;
            }
        }
        if (matches != 1 || matchingEntry == null || matchingEntry.isDirectory()) {
            throw failure(
                    "AAB must contain exactly one non-directory entry " + name
                            + ", found " + matches);
        }
        return matchingEntry;
    }

    private static void verifySplitConfig(Config.BundleConfig bundleConfig)
            throws VerificationException {
        if (!bundleConfig.hasOptimizations()
                || !bundleConfig.getOptimizations().hasSplitsConfig()) {
            throw failure("BundleConfig.pb has no explicit splits configuration");
        }

        List<Config.SplitDimension> dimensions =
                bundleConfig.getOptimizations().getSplitsConfig().getSplitDimensionList();
        Map<Config.SplitDimension.Value, Config.SplitDimension> byValue = new LinkedHashMap<>();
        for (Config.SplitDimension dimension : dimensions) {
            Config.SplitDimension.Value value = dimension.getValue();
            if (value == Config.SplitDimension.Value.UNSPECIFIED_VALUE
                    || value == Config.SplitDimension.Value.UNRECOGNIZED
                    || byValue.put(value, dimension) != null) {
                throw failure("BundleConfig.pb has duplicate or unknown split dimensions");
            }
        }

        Config.SplitDimension language = byValue.get(Config.SplitDimension.Value.LANGUAGE);
        if (language == null || !language.getNegate()) {
            throw failure(
                    "Language splits are enabled or unspecified; expected LANGUAGE/negate=true");
        }

        verifyDimensionRemainsEnabled(byValue, Config.SplitDimension.Value.ABI);
        verifyDimensionRemainsEnabled(byValue, Config.SplitDimension.Value.SCREEN_DENSITY);
    }

    private static void verifyDimensionRemainsEnabled(
            Map<Config.SplitDimension.Value, Config.SplitDimension> dimensions,
            Config.SplitDimension.Value value
    ) throws VerificationException {
        Config.SplitDimension dimension = dimensions.get(value);
        if (dimension != null && dimension.getNegate()) {
            throw failure(value + " splits are disabled unexpectedly");
        }
    }

    private static void verifyRequiredResources(Resources.ResourceTable resourceTable)
            throws VerificationException {
        Map<String, Map<String, Set<String>>> actual = new LinkedHashMap<>();
        boolean foundApplicationPackage = false;

        for (Resources.Package resourcePackage : resourceTable.getPackageList()) {
            if (!APPLICATION_PACKAGE.equals(resourcePackage.getPackageName())) {
                continue;
            }
            foundApplicationPackage = true;
            for (Resources.Type type : resourcePackage.getTypeList()) {
                if (!"string".equals(type.getName())) {
                    continue;
                }
                for (Resources.Entry entry : type.getEntryList()) {
                    ExpectedResource expected = REQUIRED_RESOURCES.get(entry.getName());
                    if (expected == null) {
                        continue;
                    }
                    for (Resources.ConfigValue configValue : entry.getConfigValueList()) {
                        if (!configValue.hasValue()
                                || !configValue.getValue().hasItem()
                                || !configValue.getValue().getItem().hasStr()) {
                            continue;
                        }

                        String locale = resourceLocale(configValue);
                        if (locale == null) {
                            continue;
                        }
                        actual.computeIfAbsent(entry.getName(), ignored -> new LinkedHashMap<>())
                                .computeIfAbsent(locale, ignored -> new LinkedHashSet<>())
                                .add(configValue.getValue().getItem().getStr().getValue());
                    }
                }
            }
        }

        if (!foundApplicationPackage) {
            throw failure("base/resources.pb has no application package " + APPLICATION_PACKAGE);
        }

        for (Map.Entry<String, ExpectedResource> required : REQUIRED_RESOURCES.entrySet()) {
            Map<String, Set<String>> values = actual.get(required.getKey());
            if (values == null) {
                throw failure("Missing required string resource " + required.getKey());
            }
            requireExactValue(required.getKey(), "default", values.get(""),
                    required.getValue().defaultJapanese);
            requireExactValue(required.getKey(), "en", values.get("en"),
                    required.getValue().english);
        }
    }

    /**
     * Only accept a plain default or language-only configuration. A values-night
     * or values-land resource with an empty locale is not the app's default
     * language resource.
     */
    private static String resourceLocale(Resources.ConfigValue configValue) {
        Configuration configuration = configValue.hasConfig()
                ? configValue.getConfig()
                : Configuration.getDefaultInstance();
        if (configuration.equals(Configuration.getDefaultInstance())) {
            return "";
        }
        if (configuration.equals(Configuration.newBuilder().setLocale("en").build())) {
            return "en";
        }
        return null;
    }

    private static void requireExactValue(
            String resourceName,
            String locale,
            Set<String> actual,
            String expected
    ) throws VerificationException {
        if (actual == null || actual.size() != 1 || !actual.contains(expected)) {
            throw failure(
                    "Resource " + resourceName + " locale " + locale
                            + " must be exactly " + expected + ", got " + actual);
        }
    }

    private static VerificationException failure(String message) {
        return new VerificationException(message);
    }

    private static VerificationException failure(String message, Throwable cause) {
        return new VerificationException(message, cause);
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }

    public static final class VerificationException extends IOException {
        private VerificationException(String message) {
            super(message);
        }

        private VerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ExpectedResource {
        private final String defaultJapanese;
        private final String english;

        private ExpectedResource(String defaultJapanese, String english) {
            this.defaultJapanese = defaultJapanese;
            this.english = english;
        }
    }
}
