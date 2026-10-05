import React from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { isQuarantinedEntry, quarantineBadgeText } from '../utils/quarantineBadge';

export interface Recording {
  id: string;
  title: string;
  date: string;
  size: string;
  raw: string;
  cleaned: string;
  wave: number[];
  // Quarantined sessions stay visible with an explicit local-only badge
  // (ticket #136) — hiding them would look like data loss.
  quarantined?: boolean;
}

// Memoized recording card for FlatList performance
export const RecordingCard = React.memo(({ item, isSelected, onPress }: {
  item: Recording;
  isSelected: boolean;
  onPress: () => void;
}) => (
  <TouchableOpacity
    style={[styles.recordingCard, isSelected && styles.recordingCardSelected]}
    onPress={onPress}
  >
    <View style={styles.recCardHeader}>
      <View>
        <Text style={styles.recTitle}>{item.title}</Text>
        <Text style={styles.recDate}>{item.date} • {item.size}</Text>
      </View>
      <Text style={styles.recChevron}>➔</Text>
    </View>
    {isQuarantinedEntry(item) && (
      <View style={styles.recQuarantineBadge}>
        <Text style={styles.recQuarantineBadgeText}>🔒 {quarantineBadgeText()}</Text>
      </View>
    )}
    <View style={styles.recWaveContainer}>
      {item.wave.map((h, i) => (
        <View
          key={i}
          style={[
            styles.recWaveBar,
            { height: h },
            isSelected ? { backgroundColor: '#62f9ee' } : { backgroundColor: '#859491' }
          ]}
        />
      ))}
    </View>
  </TouchableOpacity>
));

const styles = StyleSheet.create({
  recordingCard: {
    backgroundColor: '#161d1c', // Low container background
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  recordingCardSelected: {
    borderColor: '#62f9ee', // Active outline
  },
  recCardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  recTitle: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#ffffff',
  },
  recDate: {
    fontSize: 12,
    color: '#859491',
    marginTop: 2,
  },
  recChevron: {
    fontSize: 16,
    color: '#859491',
  },
  recWaveContainer: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    height: 30,
    marginTop: 4,
  },
  recWaveBar: {
    width: 3,
    marginRight: 2,
    borderRadius: 1.5,
  },
  recQuarantineBadge: {
    alignSelf: 'flex-start',
    backgroundColor: '#5c1a1a',
    borderColor: '#ff6b6b',
    borderWidth: 1,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
    marginTop: 6,
  },
  recQuarantineBadgeText: {
    fontSize: 10,
    fontWeight: 'bold',
    color: '#ffffff',
  },
});
