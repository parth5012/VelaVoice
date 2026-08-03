import { calculateEditDistance, getEdits } from './editCalculator';

function assert(expr: boolean, message: string) {
  if (!expr) {
    throw new Error('Assertion failed: ' + message);
  }
}

function runTests() {
  console.log('Running editCalculator tests...');

  // Test calculateEditDistance
  assert(calculateEditDistance('hello', 'hello') === 0, 'distance is 0 for identical strings');
  assert(calculateEditDistance('hello', 'hell') === 1, 'distance is 1 for deletion');
  assert(calculateEditDistance('hello', 'helloo') === 1, 'distance is 1 for insertion');
  assert(calculateEditDistance('hello', 'hallo') === 1, 'distance is 1 for substitution');
  assert(calculateEditDistance('kitten', 'sitting') === 3, 'kitten -> sitting is 3');

  // Test getEdits - Identical
  const edits1 = getEdits('hello world', 'hello world');
  assert(edits1.length === 0, 'No edits for identical string');

  // Test getEdits - Substitution
  const edits2 = getEdits('hello cat', 'hello dog');
  assert(edits2.length === 1, 'One edit for substitution');
  assert(edits2[0].type === 'substitution', 'Edit type is substitution');
  assert(edits2[0].original === 'cat', 'Original word is cat');
  assert(edits2[0].corrected === 'dog', 'Corrected word is dog');
  assert(edits2[0].position === 1, 'Position is 1');

  // Test getEdits - Deletion
  const edits3 = getEdits('hello cat world', 'hello world');
  assert(edits3.length === 1, 'One edit for deletion');
  assert(edits3[0].type === 'deletion', 'Edit type is deletion');
  assert(edits3[0].original === 'cat', 'Original word is cat');
  assert(edits3[0].corrected === '', 'Corrected is empty string');
  assert(edits3[0].position === 1, 'Position is 1');

  // Test getEdits - Insertion
  const edits4 = getEdits('hello world', 'hello big world');
  assert(edits4.length === 1, 'One edit for insertion');
  assert(edits4[0].type === 'insertion', 'Edit type is insertion');
  assert(edits4[0].original === '', 'Original is empty string');
  assert(edits4[0].corrected === 'big', 'Corrected word is big');
  assert(edits4[0].position === 1, 'Position is 1');

  console.log('✅ All editCalculator tests passed!');
}

try {
  runTests();
} catch (e: any) {
  console.error('❌ Tests failed:', e.message);
  process.exit(1);
}
