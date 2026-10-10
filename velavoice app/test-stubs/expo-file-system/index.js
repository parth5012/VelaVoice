// Test stub for expo-file-system: only these three members are referenced by
// ModelManager.ts, and the test suites never run an actual download.
module.exports = {
  documentDirectory: 'file://mock-dir/',
  deleteAsync: async () => {},
  createDownloadResumable: () => ({
    downloadAsync: async () => ({ uri: 'file://mock-dir/downloaded' }),
    resumeAsync: async () => ({ uri: 'file://mock-dir/downloaded' }),
  }),
};
