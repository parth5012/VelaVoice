module.exports = ({ config }) => {
  const isDev = process.env.APP_ENV === 'development';
  return {
    ...config,
    name: isDev ? 'VelaVoice (dev)' : 'VelaVoice',
  };
};
