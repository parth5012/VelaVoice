const { withAndroidManifest, withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

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

  // 2. Copy files and patch resources
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
