/**
 * Module: src/theme/appStyles
 * Intent: Tab-level layout primitives shared by the Hub, Studio, and Engine screens.
 * Public API: sharedScreenStyles
 * Maintenance: Add a key here only when 2+ screens genuinely share it; otherwise colocate.
 */
import { StyleSheet } from 'react-native';

export const sharedScreenStyles = StyleSheet.create({
  tabContent: {
    flex: 1,
    paddingHorizontal: 20,
    paddingTop: 15,
  },
  hubTitle: {
    fontSize: 26,
    fontWeight: 'bold',
    color: '#ffffff',
  },
  studioSubtitle: {
    fontSize: 14,
    color: '#859491',
    marginTop: -16,
    marginBottom: 20,
  },
  sandboxResult: {
    marginTop: 15,
    backgroundColor: '#1a2120',
    padding: 12,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#3c4948',
  },
  sandboxResultText: {
    fontSize: 14,
    color: '#dde4e2',
    lineHeight: 20,
  },
});
