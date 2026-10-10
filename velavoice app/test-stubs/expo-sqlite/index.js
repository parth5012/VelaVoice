// Test stub for expo-sqlite (quarantineEgress.test.ts + the ModelManager.js /
// api.js JS-mirror copies it loads). Records write SQL so the quarantine
// egress tests can assert "zero INSERTs for a refused correction"; reads
// return empty result sets. Shared instance: every importer resolves to this
// same file path, so __writes is one array across TS and JS copies.
const writes = [];

function createDb() {
  return {
    execAsync: async () => {},
    runAsync: async (sql, params) => {
      writes.push({ sql, params: params || [] });
    },
    getAllAsync: async () => [],
    closeAsync: async () => {},
  };
}

module.exports = {
  __writes: writes,
  __reset: () => {
    writes.length = 0;
  },
  openDatabaseAsync: async () => createDb(),
};
