const { withAndroidManifest, withDangerousMod } = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

function withVoiceIme(config) {
  // 1. Android Manifest modification
  config = withAndroidManifest(config, async (config) => {
    const mainApplication = config.modResults.manifest.application[0];
    
    // Add the service if it doesn't exist
    if (!mainApplication.service) {
      mainApplication.service = [];
    }
    
    const serviceExists = mainApplication.service.some(
      (s) => s.$['android:name'] === '.VoiceInputMethodService'
    );
    
    if (!serviceExists) {
      mainApplication.service.push({
        $: {
          'android:name': '.VoiceInputMethodService',
          'android:label': 'Vela Voice Input',
          'android:permission': 'android.permission.BIND_INPUT_METHOD',
          'android:exported': 'true',
        },
        'meta-data': [
          {
            $: {
              'android:name': 'android.view.im',
              'android:resource': '@xml/method',
            },
          },
        ],
        'intent-filter': [
          {
            action: [
              {
                $: {
                  'android:name': 'android.view.InputMethod',
                },
              },
            ],
          },
        ],
      });
    }
    
    return config;
  });

  // 2. Copy method.xml and Kotlin files
  config = withDangerousMod(config, [
    'android',
    async (config) => {
      const { projectRoot } = config.modRequest;
      
      // Path to res/xml/method.xml
      const xmlDir = path.join(projectRoot, 'android/app/src/main/res/xml');
      if (!fs.existsSync(xmlDir)) {
        fs.mkdirSync(xmlDir, { recursive: true });
      }
      
      const xmlContent = `<?xml version="1.0" encoding="utf-8"?>
<input-method xmlns:android="http://schemas.android.com/apk/res/android"
    android:settingsActivity="com.velavoice.app.MainActivity" />
`;
      fs.writeFileSync(path.join(xmlDir, 'method.xml'), xmlContent, 'utf-8');

      // Path to Kotlin service target package
      const packageDir = path.join(projectRoot, 'android/app/src/main/java/com/velavoice/app');
      if (!fs.existsSync(packageDir)) {
        fs.mkdirSync(packageDir, { recursive: true });
      }
      
      const srcKotlinPath = path.join(projectRoot, 'src/native/VoiceInputMethodService.kt');
      const destKotlinPath = path.join(packageDir, 'VoiceInputMethodService.kt');
      
      if (fs.existsSync(srcKotlinPath)) {
        fs.copyFileSync(srcKotlinPath, destKotlinPath);
      } else {
        console.warn(`Source Kotlin file not found at ${srcKotlinPath}`);
      }
      
      return config;
    },
  ]);

  return config;
}

module.exports = withVoiceIme;
