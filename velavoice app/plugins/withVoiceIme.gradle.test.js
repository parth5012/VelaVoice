/**
 * TDD RED test for wayfinder ticket 1.2 (#66):
 * Wire sdk/ into the Expo Android Gradle build via composite build.
 *
 * Expects `velavoice app/plugins/withVoiceIme.js` to export pure helpers:
 *   - ensureIncludeBuild(settingsGradleContents) -> contents with includeBuild("../../sdk")
 *   - ensureSdkDeps(appBuildGradleContents) -> contents with vela-core + vela-voice-ui deps
 *
 * Run: node plugins/withVoiceIme.gradle.test.js
 */
const assert = require('assert');
const path = require('path');

const pluginPath = path.join(__dirname, 'withVoiceIme.js');
let plugin;
try {
  plugin = require(pluginPath);
} catch (e) {
  console.error(`FAIL: cannot require withVoiceIme.js: ${e.message}`);
  process.exit(1);
}

const { ensureIncludeBuild, ensureSdkDeps } = plugin;

try {
  assert.strictEqual(
    typeof ensureIncludeBuild,
    'function',
    'withVoiceIme.js must export ensureIncludeBuild(settingsContents)'
  );
  assert.strictEqual(
    typeof ensureSdkDeps,
    'function',
    'withVoiceIme.js must export ensureSdkDeps(appBuildContents)'
  );

  // --- settings.gradle: inject composite build ---
  const emptySettings = `rootProject.name = 'velavoice'\ninclude ':app'\n`;
  const withBuild = ensureIncludeBuild(emptySettings);
  assert.ok(
    withBuild.includes('includeBuild("../../sdk")'),
    'settings.gradle must contain includeBuild("../../sdk") after ensureIncludeBuild'
  );
  // idempotent: second application must not duplicate
  const twice = ensureIncludeBuild(withBuild);
  assert.strictEqual(
    twice.split('includeBuild("../../sdk")').length - 1,
    1,
    'ensureIncludeBuild must be idempotent (exactly one includeBuild)'
  );

  // --- app/build.gradle: inject SDK deps ---
  const emptyAppGradle = `dependencies {\n    implementation "com.facebook.react:react-native:+"\n}\n`;
  const withDeps = ensureSdkDeps(emptyAppGradle);
  assert.ok(
    withDeps.includes('com.velavoice.sdk:vela-core:1.0.0'),
    'app build.gradle must contain vela-core dep after ensureSdkDeps'
  );
  assert.ok(
    withDeps.includes('com.velavoice.sdk:vela-voice-ui:1.0.0'),
    'app build.gradle must contain vela-voice-ui dep after ensureSdkDeps'
  );
  const twiceDeps = ensureSdkDeps(withDeps);
  assert.strictEqual(
    twiceDeps.split('com.velavoice.sdk:vela-core:1.0.0').length - 1,
    1,
    'ensureSdkDeps must be idempotent for vela-core'
  );
  assert.strictEqual(
    twiceDeps.split('com.velavoice.sdk:vela-voice-ui:1.0.0').length - 1,
    1,
    'ensureSdkDeps must be idempotent for vela-voice-ui'
  );

  console.log('PASS: withVoiceIme Gradle wiring helpers behave correctly (1.2 GREEN)');
} catch (e) {
  console.error(`FAIL: ${e.message}`);
  process.exit(1);
}
