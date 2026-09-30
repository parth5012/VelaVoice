let withAndroidManifest;
let withDangerousMod;
let withSettingsGradle;
let withAppBuildGradle;
try {
  ({
    withAndroidManifest,
    withDangerousMod,
    withSettingsGradle,
    withAppBuildGradle,
  } = require('@expo/config-plugins'));
} catch (e) {
  // Allow pure-helper unit tests to run without expo installed (no node_modules
  // in CI worktrees). Production prebuild always has @expo/config-plugins.
  const identity = (config) => config;
  withAndroidManifest = identity;
  withDangerousMod = identity;
  withSettingsGradle = identity;
  withAppBuildGradle = identity;
}
const fs = require('fs');
const path = require('path');

// Composite-build include for the SDK (relative from android/settings.gradle
// to <repo>/sdk). Valid in both Groovy and Kotlin DSL.
const SDK_COMPOSITE_INCLUDE = 'includeBuild("../../sdk")';
// Coordinates published by sdk/ (see sdk/*/build.gradle.kts). vela-core pulls
// vela-whisper + vela-cleaner transitively; vela-voice-ui covers ui.* imports.
// The vela-whisper module registers its CMake target itself, so including the
// composite is what produces libwhisper.so — no app-side CMake needed.
const SDK_APP_DEPS = [
  'implementation("com.velavoice.sdk:vela-core:1.0.0")',
  'implementation("com.velavoice.sdk:vela-voice-ui:1.0.0")',
];

function ensureIncludeBuild(contents) {
  const src = String(contents === null || contents === undefined ? '' : contents);
  if (src.includes(SDK_COMPOSITE_INCLUDE)) return src;
  const trimmed = src.endsWith('\n') ? src : src + '\n';
  return (
    trimmed +
    '\n// withVoiceIme: composite build for the Vela SDK (provides com.velavoice.sdk.* + libwhisper.so)\n' +
    SDK_COMPOSITE_INCLUDE +
    '\n'
  );
}

function ensureSdkDeps(contents) {
  let src = String(contents === null || contents === undefined ? '' : contents);
  const missing = SDK_APP_DEPS.filter((dep) => {
    const coord = dep.slice(dep.indexOf('com.velavoice.sdk'));
    const shortCoord = coord.replace(/^com\.velavoice\.sdk:/, '').split(':')[0];
    return !src.includes('com.velavoice.sdk:' + shortCoord);
  });
  if (missing.length === 0) return src;
  const block =
    '\n// withVoiceIme: Vela SDK (composite build in settings.gradle substitutes these)\n' +
    missing.map((d) => '    ' + d).join('\n') +
    '\n';
  const match = src.match(/dependencies\s*\{/);
  if (match) {
    const idx = match.index + match[0].length;
    return src.slice(0, idx) + block + src.slice(idx);
  }
  const trimmed = src.endsWith('\n') ? src : src + '\n';
  return trimmed + '\ndependencies {' + block + '}\n';
}

function withVoiceIme(config) {
  // 1. Android Manifest modification
  config = withAndroidManifest(config, async (config) => {
    const mainApplication = config.modResults.manifest.application[0];

    // Add IME service if it doesn't exist
    if (!mainApplication.service) {
      mainApplication.service = [];
    }

    const serviceExists = mainApplication.service.some(
      (s) => s.$['android:name'] === '.VoiceInputMethodService'
    );

    if (!serviceExists) {
      mainApplication.service.push({
        '$': {
          'android:name': '.VoiceInputMethodService',
          'android:label': 'Vela Voice Input',
          'android:permission': 'android.permission.BIND_INPUT_METHOD',
          'android:exported': 'true',
        },
        'meta-data': [
          {
            '$': {
              'android:name': 'android.view.im',
              'android:resource': '@xml/method',
            },
          },
        ],
        'intent-filter': [
          {
            action: [
              {
                '$': {
                  'android:name': 'android.view.InputMethod',
                },
              },
            ],
          },
        ],
      });
    }

    // Add Accessibility Service if it doesn't exist
    const accessibilityServiceExists = mainApplication.service.some(
      (s) => s.$['android:name'] === '.VoiceAccessibilityService'
    );

    if (!accessibilityServiceExists) {
      mainApplication.service.push({
        '$': {
          'android:name': '.VoiceAccessibilityService',
          'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
          'android:exported': 'true',
        },
        'meta-data': [
          {
            '$': {
              'android:name': 'android.accessibilityservice',
              'android:resource': '@xml/accessibility_service_config',
            },
          },
        ],
        'intent-filter': [
          {
            action: [
              {
                '$': {
                  'android:name': 'android.accessibilityservice.AccessibilityService',
                },
              },
            ],
          },
        ],
      });
    }

    // Ensure uses-permission array exists
    if (!config.modResults.manifest['uses-permission']) {
      config.modResults.manifest['uses-permission'] = [];
    }

    const addPermission = (name) => {
      const exists = config.modResults.manifest['uses-permission'].some(
        (p) => p.$['android:name'] === name
      );
      if (!exists) {
        config.modResults.manifest['uses-permission'].push({
          '$': { 'android:name': name },
        });
      }
    };

    addPermission('android.permission.RECORD_AUDIO');
    addPermission('android.permission.INTERNET');

    // Add SYSTEM_ALERT_WINDOW permission if it doesn't exist
    const hasSystemAlertWindow = config.modResults.manifest['uses-permission'].some(
      (p) => p.$['android:name'] === 'android.permission.SYSTEM_ALERT_WINDOW'
    );

    if (!hasSystemAlertWindow) {
      config.modResults.manifest['uses-permission'].push({
        '$': {
          'android:name': 'android.permission.SYSTEM_ALERT_WINDOW',
        },
      });
    }

    return config;
  });

  // 2. Gradle wiring: composite build + SDK deps (ticket 1.2 / #66).
  // settings.gradle -> includeBuild("../../sdk") so com.velavoice.sdk:*
  // resolves from source and :vela-whisper's CMake target builds libwhisper.so.
  config = withSettingsGradle(config, async (config) => {
    if (config.modResults && typeof config.modResults.contents === 'string') {
      config.modResults.contents = ensureIncludeBuild(config.modResults.contents);
    }
    return config;
  });

  // app/build.gradle -> implementation deps substituted by the composite.
  config = withAppBuildGradle(config, async (config) => {
    if (config.modResults && typeof config.modResults.contents === 'string') {
      config.modResults.contents = ensureSdkDeps(config.modResults.contents);
    }
    return config;
  });

  // 3. Copy files and patch resources
  config = withDangerousMod(config, [
    'android',
    async (config) => {
      const projectRoot = config.modRequest.projectRoot;

      // Create res/xml directory if it doesn't exist
      const xmlDir = path.join(projectRoot, 'android/app/src/main/res/xml');
      if (!fs.existsSync(xmlDir)) {
        fs.mkdirSync(xmlDir, { recursive: true });
      }

      // Write method.xml
      const xmlContent = `<?xml version="1.0" encoding="utf-8"?>
<input-method xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="com.velavoice.app.MainActivity" />`;
      fs.writeFileSync(path.join(xmlDir, 'method.xml'), xmlContent, 'utf-8');

      // Write accessibility_service_config.xml
      const accessibilityXmlContent = `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:description="@string/accessibility_service_description" />`;
      fs.writeFileSync(
        path.join(xmlDir, 'accessibility_service_config.xml'),
        accessibilityXmlContent,
        'utf-8'
      );

      // Create target Kotlin package directory
      const packageDir = path.join(projectRoot, 'android/app/src/main/java/com/velavoice/app');
      if (!fs.existsSync(packageDir)) {
        fs.mkdirSync(packageDir, { recursive: true });
      }

      // Copy Kotlin files
      const filesToCopy = [
        'VoiceInputMethodService.kt',
        'VoiceAccessibilityService.kt',
        'ModelVerifierModule.kt',
        'VoiceImePackage.kt',
        'TranscriptionStorage.kt',
        'GoogleDriveSyncModule.kt',
      ];

      for (const fileName of filesToCopy) {
        const srcPath = path.join(projectRoot, 'src/native', fileName);
        const destPath = path.join(packageDir, fileName);
        if (fs.existsSync(srcPath)) {
          fs.copyFileSync(srcPath, destPath);
        } else {
          console.warn(`Source Kotlin file not found at ${srcPath}`);
        }
      }

      // Patch strings.xml Accessibility Service description
      const stringsPath = path.join(projectRoot, 'android/app/src/main/res/values/strings.xml');
      if (fs.existsSync(stringsPath)) {
        let stringsContent = fs.readFileSync(stringsPath, 'utf-8');
        if (!stringsContent.includes('accessibility_service_description')) {
          const insertIndex = stringsContent.lastIndexOf('</resources>');
          if (insertIndex !== -1) {
            stringsContent =
              stringsContent.substring(0, insertIndex) +
              '    <string name="accessibility_service_description">Enables Vela Voice floating microphone button to type spoken text in any active app.</string>\n' +
              stringsContent.substring(insertIndex);
            fs.writeFileSync(stringsPath, stringsContent, 'utf-8');
            console.log('Successfully patched strings.xml accessibility description');
          }
        }
      } else {
        console.warn('strings.xml not found');
      }

      // Patch MainApplication.kt to register VoiceImePackage
      const mainAppPath = path.join(packageDir, 'MainApplication.kt');
      if (fs.existsSync(mainAppPath)) {
        let content = fs.readFileSync(mainAppPath, 'utf-8');
        if (!content.includes('VoiceImePackage')) {
          const target = 'return PackageList(this).packages';
          const replacement = 'return PackageList(this).packages + listOf(VoiceImePackage())';
          if (content.includes(target)) {
            content = content.replace(target, replacement);
            fs.writeFileSync(mainAppPath, content, 'utf-8');
            console.log('Successfully patched MainApplication.kt to include VoiceImePackage');
          } else {
            console.warn('Could not find PackageList(this).packages in MainApplication.kt');
          }
        }
      } else {
        console.warn(`MainApplication.kt not found at ${mainAppPath}`);
      }

      return config;
    },
  ]);

  return config;
}

module.exports = withVoiceIme;
module.exports.ensureIncludeBuild = ensureIncludeBuild;
module.exports.ensureSdkDeps = ensureSdkDeps;
module.exports.SDK_COMPOSITE_INCLUDE = SDK_COMPOSITE_INCLUDE;
module.exports.SDK_APP_DEPS = SDK_APP_DEPS;
