import com.android.aapt.ConfigurationOuterClass.Configuration;
import com.android.aapt.Resources;
import com.android.bundle.Config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Plain-JVM regression fixtures for {@link BundleLanguageVerifier}.
 *
 * <p>The fixtures are built from the same generated protobuf types as the
 * production artifact, so they exercise the parser and not a source-text
 * approximation of the bundle format.</p>
 */
public final class BundleLanguageVerifierTest {
    private static final String PACKAGE_NAME = "jp.rimtty.codematch";

    private BundleLanguageVerifierTest() {
    }

    public static void main(String[] args) throws Exception {
        Path fixtureDirectory = Files.createTempDirectory("bundle-language-verifier-");
        try {
            expectPass("valid", writeBundle(
                    fixtureDirectory.resolve("valid.aab"),
                    validConfig(),
                    resources(true, true, false)
            ));
            expectFailure("missing language dimension", writeBundle(
                    fixtureDirectory.resolve("missing-language.aab"),
                    config(false, null),
                    resources(true, true, false)
            ), "Language splits are enabled or unspecified");
            expectFailure("language split enabled", writeBundle(
                    fixtureDirectory.resolve("language-enabled.aab"),
                    config(true, false),
                    resources(true, true, false)
            ), "Language splits are enabled or unspecified");
            expectFailure("default Japanese resources missing", writeBundle(
                    fixtureDirectory.resolve("missing-default.aab"),
                    validConfig(),
                    resources(false, true, false)
            ), "locale default");
            expectFailure("English resources missing", writeBundle(
                    fixtureDirectory.resolve("missing-english.aab"),
                    validConfig(),
                    resources(true, false, false)
            ), "locale en");
            expectFailure("default values are English", writeBundle(
                    fixtureDirectory.resolve("wrong-default.aab"),
                    validConfig(),
                    resources(true, true, true)
            ), "locale default");
            expectFailure("qualifier is not default", writeBundle(
                    fixtureDirectory.resolve("qualified-default.aab"),
                    validConfig(),
                    resourcesWithQualifiedDefault()
            ), "locale default");
            expectFailure("ABI split disabled", writeBundle(
                    fixtureDirectory.resolve("abi-disabled.aab"),
                    configWithSplitFlags(true, false, true, true),
                    resources(true, true, false)
            ), "ABI splits are disabled unexpectedly");
            expectFailure("density split disabled", writeBundle(
                    fixtureDirectory.resolve("density-disabled.aab"),
                    configWithSplitFlags(false, true, true, true),
                    resources(true, true, false)
            ), "SCREEN_DENSITY splits are disabled unexpectedly");
            expectFailure("BundleConfig missing", writeBundleWithoutConfig(
                    fixtureDirectory.resolve("missing-config.aab"),
                    resources(true, true, false)
            ), "BundleConfig.pb");
            expectFailure("BundleConfig malformed", writeMalformedBundle(
                    fixtureDirectory.resolve("malformed-config.aab"),
                    "BundleConfig.pb",
                    resources(true, true, false)
            ), "BundleConfig.pb is missing or malformed");
            expectFailure("resource table malformed", writeMalformedBundle(
                    fixtureDirectory.resolve("malformed-resources.aab"),
                    "base/resources.pb",
                    validConfig()
            ), "base/resources.pb is missing or malformed");
            System.out.println("BundleLanguageVerifier fixtures passed");
        } finally {
            deleteRecursively(fixtureDirectory);
        }
    }

    private static Config.BundleConfig validConfig() {
        return config(true, true);
    }

    /**
     * @param includeLanguage whether to add the LANGUAGE dimension
     * @param languageNegate null means no LANGUAGE dimension
     */
    private static Config.BundleConfig config(boolean includeLanguage, Boolean languageNegate) {
        return configWithSplitFlags(false, false, includeLanguage, languageNegate);
    }

    private static Config.BundleConfig configWithSplitFlags(
            boolean abiNegate,
            boolean densityNegate,
            boolean includeLanguage,
            Boolean languageNegate
    ) {
        Config.SplitsConfig.Builder splits = Config.SplitsConfig.newBuilder()
                .addSplitDimension(split(Config.SplitDimension.Value.ABI, abiNegate))
                .addSplitDimension(split(Config.SplitDimension.Value.SCREEN_DENSITY, densityNegate));
        if (includeLanguage) {
            splits.addSplitDimension(split(
                    Config.SplitDimension.Value.LANGUAGE,
                    languageNegate != null && languageNegate
            ));
        }
        return Config.BundleConfig.newBuilder()
                .setOptimizations(Config.Optimizations.newBuilder().setSplitsConfig(splits))
                .build();
    }

    private static Config.SplitDimension split(
            Config.SplitDimension.Value value,
            boolean negate
    ) {
        return Config.SplitDimension.newBuilder()
                .setValue(value)
                .setNegate(negate)
                .build();
    }

    private static Resources.ResourceTable resources(
            boolean includeDefault,
            boolean includeEnglish,
            boolean defaultIsEnglish
    ) {
        Resources.Type.Builder strings = Resources.Type.newBuilder().setName("string");
        addString(strings, "scan_wait_qr_title", "QRコードを読み取ってください", "Scan a QR code",
                includeDefault, includeEnglish, defaultIsEnglish);
        addString(strings, "settings_title", "設定", "Settings",
                includeDefault, includeEnglish, defaultIsEnglish);
        addString(strings, "history_title", "照合履歴", "Match history",
                includeDefault, includeEnglish, defaultIsEnglish);

        return Resources.ResourceTable.newBuilder()
                .addPackage(Resources.Package.newBuilder()
                        .setPackageName(PACKAGE_NAME)
                        .addType(strings))
                .build();
    }

    private static Resources.ResourceTable resourcesWithQualifiedDefault() {
        Resources.Type.Builder strings = Resources.Type.newBuilder().setName("string");
        addStringWithValues(
                strings,
                "scan_wait_qr_title",
                valueWithConfiguration(
                        Configuration.newBuilder().setScreenWidthDp(320).build(),
                        "QRコードを読み取ってください"
                ),
                value("en", "Scan a QR code")
        );
        addStringWithValues(
                strings,
                "settings_title",
                valueWithConfiguration(
                        Configuration.newBuilder().setScreenWidthDp(320).build(),
                        "設定"
                ),
                value("en", "Settings")
        );
        addStringWithValues(
                strings,
                "history_title",
                valueWithConfiguration(
                        Configuration.newBuilder().setScreenWidthDp(320).build(),
                        "照合履歴"
                ),
                value("en", "Match history")
        );
        return Resources.ResourceTable.newBuilder()
                .addPackage(Resources.Package.newBuilder()
                        .setPackageName(PACKAGE_NAME)
                        .addType(strings))
                .build();
    }

    private static void addString(
            Resources.Type.Builder strings,
            String name,
            String japanese,
            String english,
            boolean includeDefault,
            boolean includeEnglish,
            boolean defaultIsEnglish
    ) {
        Resources.Entry.Builder entry = Resources.Entry.newBuilder().setName(name);
        if (includeDefault) {
            entry.addConfigValue(value("", defaultIsEnglish ? english : japanese));
        }
        if (includeEnglish) {
            entry.addConfigValue(value("en", english));
        }
        strings.addEntry(entry);
    }

    private static void addStringWithValues(
            Resources.Type.Builder strings,
            String name,
            Resources.ConfigValue defaultValue,
            Resources.ConfigValue englishValue
    ) {
        strings.addEntry(Resources.Entry.newBuilder()
                .setName(name)
                .addConfigValue(defaultValue)
                .addConfigValue(englishValue));
    }

    private static Resources.ConfigValue value(String locale, String text) {
        Resources.Item item = Resources.Item.newBuilder()
                .setStr(Resources.String.newBuilder().setValue(text))
                .build();
        Resources.Value value = Resources.Value.newBuilder().setItem(item).build();
        Resources.ConfigValue.Builder configValue = Resources.ConfigValue.newBuilder()
                .setValue(value);
        if (!locale.isEmpty()) {
            configValue.setConfig(Configuration.newBuilder().setLocale(locale));
        }
        return configValue.build();
    }

    private static Resources.ConfigValue valueWithConfiguration(
            Configuration configuration,
            String text
    ) {
        Resources.Item item = Resources.Item.newBuilder()
                .setStr(Resources.String.newBuilder().setValue(text))
                .build();
        return Resources.ConfigValue.newBuilder()
                .setConfig(configuration)
                .setValue(Resources.Value.newBuilder().setItem(item))
                .build();
    }

    private static Path writeBundle(
            Path path,
            Config.BundleConfig config,
            Resources.ResourceTable resources
    ) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            writeEntry(zip, "BundleConfig.pb", config.toByteArray());
            writeEntry(zip, "base/resources.pb", resources.toByteArray());
        }
        return path;
    }

    private static Path writeBundleWithoutConfig(
            Path path,
            Resources.ResourceTable resources
    ) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            writeEntry(zip, "base/resources.pb", resources.toByteArray());
        }
        return path;
    }

    private static Path writeMalformedBundle(
            Path path,
            String malformedEntry,
            Object otherArtifact
    ) throws IOException {
        try (OutputStream output = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            if ("BundleConfig.pb".equals(malformedEntry)) {
                writeEntry(zip, malformedEntry, new byte[]{(byte) 0x80});
                writeEntry(zip, "base/resources.pb", ((Resources.ResourceTable) otherArtifact).toByteArray());
            } else {
                writeEntry(zip, "BundleConfig.pb", ((Config.BundleConfig) otherArtifact).toByteArray());
                writeEntry(zip, malformedEntry, new byte[]{(byte) 0x80});
            }
        }
        return path;
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void expectPass(String name, Path bundle) throws Exception {
        try {
            BundleLanguageVerifier.verify(bundle);
        } catch (Exception error) {
            throw new AssertionError(name + " unexpectedly failed: " + error.getMessage(), error);
        }
    }

    private static void expectFailure(String name, Path bundle, String expectedReason)
            throws Exception {
        try {
            BundleLanguageVerifier.verify(bundle);
        } catch (BundleLanguageVerifier.VerificationException expected) {
            if (expected.getMessage() == null || !expected.getMessage().contains(expectedReason)) {
                throw new AssertionError(
                        name + " failed for an unexpected reason: " + expected.getMessage(),
                        expected
                );
            }
            return;
        }
        throw new AssertionError(name + " unexpectedly passed");
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new RuntimeException(error);
                }
            });
        }
    }
}
