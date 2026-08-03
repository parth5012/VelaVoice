const fs = require('fs');
const path = require('path');

// Read .env file manually to avoid extra dependencies
function loadEnv() {
  try {
    const envPath = path.join(__dirname, '.env');
    const content = fs.readFileSync(envPath, 'utf-8');
    const env = {};
    content.split('\n').forEach(line => {
      const trimmed = line.trim();
      if (trimmed && !trimmed.startsWith('#')) {
        const eqIdx = trimmed.indexOf('=');
        if (eqIdx > 0) {
          const key = trimmed.substring(0, eqIdx).trim();
          const val = trimmed.substring(eqIdx + 1).trim();
          env[key] = val;
        }
      }
    });
    return env;
  } catch (e) {
    return {};
  }
}

module.exports = ({ config }) => {
  const isDev = process.env.APP_ENV === 'development';
  const envVars = loadEnv();
  return {
    ...config,
    name: isDev ? 'VelaVoice (dev)' : 'VelaVoice',
    extra: {
      googleDriveClientId: envVars.GOOGLE_CLIENT_ID || '',
      googleDriveClientSecret: envVars.GOOGLE_CLIENT_SECRET || '',
      googleDriveRefreshToken: envVars.GOOGLE_REFRESH_TOKEN || '',
    },
  };
};
