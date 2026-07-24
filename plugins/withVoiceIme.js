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

    // Add RECORD_AUDIO permission if it doesn't exist
    if (!config.modResults.manifest['uses-permission']) {
      config.modResults.manifest['uses-permission'] = [];
    }
    const hasRecordAudio = config.modResults.manifest['uses-permission'].some(
      (p) => p.$['android:name'] === 'android.permission.RECORD_AUDIO'
    );
    if (!hasRecordAudio) {
      config.modResults.manifest['uses-permission'].push({
        $: {
          'android:name': 'android.permission.RECORD_AUDIO',
        },
      });
    }
    
    return config;
  });

  // 2. Copy method.xml, Kotlin files and patch MainApplication.kt
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
      
      // Copy Kotlin files
      const filesToCopy = [
        'VoiceInputMethodService.kt',
        'ModelVerifierModule.kt',
        'VoiceImePackage.kt',
        'WaveformView.kt',
        'WhisperEngine.kt',
        'TextCleaner.kt',
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
      
          // 2.5 Copy whisper JNI files and patch build.gradle
    const jniDestDir = path.join(projectRoot, 'android/app/src/main/jni');
    if (!fs.existsSync(jniDestDir)) {
      fs.mkdirSync(jniDestDir, { recursive: true });
    }
    const srcJniDir = path.join(projectRoot, 'src/native/whisper');
    if (fs.existsSync(srcJniDir)) {
      const jniFiles = fs.readdirSync(srcJniDir);
      for (const fileName of jniFiles) {
        fs.copyFileSync(path.join(srcJniDir, fileName), path.join(jniDestDir, fileName));
      }
      console.log('Successfully copied whisper JNI files');
    } else {
      console.warn('Source whisper JNI directory not found');
    }

    const buildGradlePath = path.join(projectRoot, 'android/app/build.gradle');
    if (fs.existsSync(buildGradlePath)) {
      let gradleContent = fs.readFileSync(buildGradlePath, 'utf-8');
      if (!gradleContent.includes('externalNativeBuild')) {
        const targetStr = "namespace 'com.velavoice.app'";
        const replacementStr = "namespace 'com.velavoice.app'\n      \n          externalNativeBuild {\n              cmake {\n                  path \"src/main/jni/CMakeLists.txt\"\n              }\n          }";
        gradleContent = gradleContent.replace(targetStr, replacementStr);
        fs.writeFileSync(buildGradlePath, gradleContent, 'utf-8');
        console.log('Successfully patched android/app/build.gradle with externalNativeBuild');
      }
    } else {
      console.warn('android/app/build.gradle not found');
    }

      // Patch MainApplication.kt to add VoiceImePackage
      const mainAppPath = path.join(packageDir, 'MainApplication.kt');
      if (fs.existsSync(mainAppPath)) {
        let content = fs.readFileSync(mainAppPath, 'utf-8');
        if (!content.includes('VoiceImePackage')) {
          // Replace getPackages() return
          const target = 'return PackageList(this).packages';
          const replacement = 'return PackageList(this).packages + listOf(VoiceImePackage())';
          if (content.includes(target)) {
            content = content.replace(target, replacement);
            fs.writeFileSync(mainAppPath, content, 'utf-8');
            console.log('Successfully patched MainApplication.kt to include VoiceImePackage');
          } else {
            console.warn('Could not find return PackageList(this).packages in MainApplication.kt');
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
