import React, { useState, useEffect, useMemo, useRef, useCallback, memo } from 'react';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useWindowDimensions } from 'react-native';
import { CinematicSplash } from './CinematicSplash';
import { View, Text,
  StyleSheet,
  TouchableOpacity,
  TextInput,
  Animated,
  Easing,
    StatusBar,
  ActivityIndicator,
  Image,
  Dimensions
} from 'react-native';
import { FlashList } from '@shopify/flash-list';
import AsyncStorage from '@react-native-async-storage/async-storage';

// ============================================================================
// 1. ADAPTER PROPS INTERFACE
// ============================================================================
export interface IGUser {
  pk: string;
  username: string;
  fullName: string;
  profilePicUrl: string;
  isVerified: boolean;
  isPrivate: boolean;

  lastAction?: 'unfollowed' | 'followed' | 'skipped_unavailable';
  actionTimestamp?: number;
}

export interface BridgeAdapterProps {
  isLoggedIn: boolean;
  isSyncing: boolean;
  syncProgress: { followers: number; following: number };
  followers: IGUser[];
  following: IGUser[];
  recentActivity: IGUser[];
  actionTimestamps: number[];
  whitelistPks: Set<string>;
  busyPk: string | null;
  onOpenLoginModal: () => void;
  onStartLiveSync: () => void;
  onLogout: () => void;
  onStartBatchQueue: (queue: any[]) => void;
  onInspectProfile: (username: string) => void;
  onToggleWhitelist: (pk: string) => void;
}

export type TabCategory =
  | 'DONT_FOLLOW_BACK'
  | 'FANS'
  | 'RECENTS'
  | 'MUTUALS'
  | 'WHITELISTED';

export type SortOrder = 'DEFAULT' | 'AZ' | 'ZA';

export type PacingStatus = 'SAFE' | 'PACING' | 'COOLDOWN';

// Storage Keys
const STORAGE_KEYS = {
  WHITELIST: '@ip_user_whitelist_v1',
  HANDLED: '@ip_user_handled_v1',
  ACTION_LOGS: '@ip_action_timestamps_v1',
};

// Obsidian Dark Theme Palette
const PALETTE = {
  canvas: '#090A0F',
  cardBg: '#12151F',
  cardBorder: 'rgba(255, 255, 255, 0.08)',
  textPrimary: '#FFFFFF',
  textSecondary: '#8E95A5',
  textMuted: '#52596B',
  igOrange: '#F58529',
  igPink: '#DD2A7B',
  igPurple: '#8134AF',
  crimson: '#FF3B5C',
  emerald: '#10B981',
  cyan: '#06B6D4',
  amber: '#F59E0B',
  badgeBg: 'rgba(255, 255, 255, 0.05)',
  pillActive: '#1F2436',
};

const { width: SCREEN_WIDTH } = Dimensions.get('window');

// ============================================================================
// 3. FLASHLIST MEMOIZED ROW COMPONENT
// ============================================================================
interface UserRowProps {
  user: IGUser;
  isChecked: boolean;
  isWhitelisted: boolean;
  isLoadingAction: boolean;
  isFollowing: boolean;
  isPacingCooldown: boolean;
  onToggleCheck: (pk: string) => void;
  onToggleWhitelist: (pk: string) => void;
  onExecuteSingle: (user: IGUser) => void;
  onInspect: (username: string) => void;
}

const UserCardRow = React.memo(function UserCardRow({ user, isChecked, isWhitelisted, isLoadingAction, isFollowing, isPacingCooldown, onToggleCheck, onToggleWhitelist, onExecuteSingle, onInspect }: UserRowProps) {
  const isRecents = user.lastAction !== undefined;
  const actionTitle = isFollowing ? 'Unfollow' : 'Follow';
  const actionColor = isFollowing ? '#FF3B5C' : '#10B981';
  let neonBorder = 'rgba(255,255,255,0.07)';
  if (!isFollowing && !isRecents) neonBorder = '#FF3B5C';
  if (isFollowing && !isRecents) neonBorder = '#10B981';
  if (isRecents) neonBorder = '#38BDF8';
  
  const pulseAnim = useMemo(() => new Animated.Value(0), []);

  useEffect(() => {
    if (isLoadingAction) {
      Animated.loop(
        Animated.sequence([
          Animated.timing(pulseAnim, { toValue: 1, duration: 600, useNativeDriver: false }),
          Animated.timing(pulseAnim, { toValue: 0, duration: 600, useNativeDriver: false })
        ])
      ).start();
    } else {
      pulseAnim.setValue(0);
    }
  }, [isLoadingAction, pulseAnim]);

  const animatedBorder = pulseAnim.interpolate({
    inputRange: [0, 1],
    outputRange: ['rgba(255,255,255,0.05)', actionColor]
  });

  return (
    <Animated.View style={{
      minHeight: 74, marginHorizontal: 14, marginBottom: 8, paddingVertical: 12, paddingHorizontal: 14,
      borderRadius: 18, backgroundColor: '#131724', borderWidth: 1.5, borderColor: isLoadingAction ? animatedBorder : 'rgba(255,255,255,0.05)',
      borderLeftWidth: 3, borderLeftColor: isLoadingAction ? actionColor : neonBorder, flexDirection: 'row', alignItems: 'center',
      ...(isLoadingAction && { transform: [{ scale: 0.98 }] })
    }}>
      <TouchableOpacity onPress={() => onToggleCheck(user.pk)} style={[{ width: 22, height: 22, borderRadius: 6, marginRight: 12, borderWidth: 1, borderColor: 'rgba(255,255,255,0.2)' }, isChecked && { backgroundColor: '#FF3B5C', borderColor: '#FF3B5C', justifyContent: 'center', alignItems: 'center' }]} activeOpacity={0.8}>
        {isChecked && <Text maxFontSizeMultiplier={1.15} style={{ color: '#FFF', fontWeight: 'bold', fontSize: 13 }}>✓</Text>}
      </TouchableOpacity>

      <TouchableOpacity onPress={() => onInspect(user.username)} style={{ width: 46, height: 46, borderRadius: 23, marginRight: 14, padding: 2, backgroundColor: 'transparent', borderWidth: 1.5, borderColor: neonBorder, overflow: 'hidden' }}>
        {user.profilePicUrl ? <Image source={{uri: user.profilePicUrl}} style={{ width: '100%', height: '100%', borderRadius: 23 }} /> : <View style={{ flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: '#262C40', borderRadius: 23 }}><Text maxFontSizeMultiplier={1.15} style={{ color: '#F8FAFC', fontSize: 16 }}>{user.username.charAt(0).toUpperCase()}</Text></View>}
      </TouchableOpacity>

      <TouchableOpacity style={{ flex: 1, flexShrink: 1, marginRight: 8 }} onPress={() => onInspect(user.username)} activeOpacity={0.8}>
        <View style={{ flexDirection: 'row', alignItems: 'center' }}>
          <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 15, fontWeight: '700', color: '#F8FAFC' }} numberOfLines={1}>@{user.username}</Text>
          {user.isVerified && <Text maxFontSizeMultiplier={1.15} style={{ color: '#38BDF8', fontSize: 13, marginLeft: 4 }}>✓</Text>}
        </View>
        <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 13, color: '#94A3B8', marginTop: 2 }} numberOfLines={1}>{user.fullName || 'Instagram Account'}</Text>
        {isRecents && <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 11, color: user.lastAction === 'unfollowed' ? '#F59E0B' : user.lastAction === 'skipped_unavailable' ? '#94A3B8' : '#10B981', marginTop: 2 }}>{user.lastAction === 'unfollowed' ? 'Unfollowed just now' : user.lastAction === 'skipped_unavailable' ? 'Unavailable / Skipped' : 'Followed just now'} • Tap {isFollowing ? 'Unfollow' : 'Follow'} to restore</Text>}
      </TouchableOpacity>

      <TouchableOpacity style={[{ width: 36, height: 36, borderRadius: 12, backgroundColor: 'rgba(255,255,255,0.04)', alignItems: 'center', justifyContent: 'center', marginRight: 10, flexShrink: 0 }, isWhitelisted && { backgroundColor: 'rgba(255,255,255,0.15)' }]} onPress={() => onToggleWhitelist(user.pk)} activeOpacity={0.7}>
        <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 16 }}>{isWhitelisted ? '🛡️' : '🛡'}</Text>
      </TouchableOpacity>

      <TouchableOpacity style={[{ height: 36, paddingHorizontal: 16, borderRadius: 12, justifyContent: 'center', alignItems: 'center', minWidth: 90, backgroundColor: actionColor, flexShrink: 0 }, (isLoadingAction || isPacingCooldown) && { opacity: 0.5 }]} disabled={isLoadingAction || isPacingCooldown} onPress={() => onExecuteSingle(user)} activeOpacity={0.8}>
        {isLoadingAction ? (
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <ActivityIndicator size="small" color="#FFFFFF" style={{ marginRight: 6 }} />
            <Text maxFontSizeMultiplier={1.15} style={{ color: '#FFFFFF', fontWeight: 'bold', fontSize: 12 }}>⚡ {isFollowing ? 'Unfollowing...' : 'Following...'}</Text>
          </View>
        ) : (
          <Text maxFontSizeMultiplier={1.15} style={{ color: '#FFFFFF', fontWeight: 'bold', fontSize: 13 }}>{actionTitle}</Text>
        )}
      </TouchableOpacity>
    </Animated.View>
  );
});


// ============================================================================
// 4. MAIN CONTROLLER COMPONENT: InstaPulseDashboard
// ============================================================================
export const InstaPulseDashboard: React.FC<BridgeAdapterProps> = ({
  isLoggedIn,
  isSyncing,
  syncProgress,
  followers,
  following,
  recentActivity,
    actionTimestamps,
    whitelistPks,
    busyPk,
    onToggleWhitelist,
  onOpenLoginModal,
  onStartLiveSync,
    onLogout,
  onStartBatchQueue,
  onInspectProfile,
}) => {
  // Splash & Intro State
  const insets = useSafeAreaInsets();
    const { width } = useWindowDimensions();
    const cardWidth = (width - 36) / 2;
    const [showSplash, setShowSplash] = useState(true);

  // Active Category & Filtering
  const [activeTab, setActiveTab] = useState<TabCategory>('DONT_FOLLOW_BACK');
  const [searchQuery, setSearchQuery] = useState('');
  const [sortOrder, setSortOrder] = useState<SortOrder>('DEFAULT');

  // Persistence Sets

  // Checkbox Selection & Debounced Loading
  const [checkedPks, setCheckedPks] = useState<Set<string>>(new Set());
  const [actionLoadingPks, setActionLoadingPks] = useState<Set<string>>(new Set());

  // Smart Queue Controller
          
  // Local demo fallback data state (only populated if user explicitly requests)
    
  // ==========================================================================
  // INITIALIZE PERSISTENCE FROM ASYNCSTORAGE
  // ==========================================================================
    // Record an executed action timestamp for rate pacing
  
  // Calculate rolling hourly and daily limits
  const pacingStats = useMemo(() => {
    // eslint-disable-next-line react-hooks/purity
    const now = Date.now(); // Suppress impure warning for dashboard load
    const oneHourAgo = now - 60 * 60 * 1000;
    const oneDayAgo = now - 24 * 60 * 60 * 1000;

    const hourlyCount = actionTimestamps.filter((ts) => ts >= oneHourAgo).length;
    const dailyCount = actionTimestamps.filter((ts) => ts >= oneDayAgo).length;

    let status: PacingStatus = 'SAFE';
    if (hourlyCount >= 22 || dailyCount >= 120) {
      status = 'COOLDOWN';
    } else if (hourlyCount >= 16 || dailyCount >= 85) {
      status = 'PACING';
    }

    return { hourlyCount, dailyCount, status };
  }, [actionTimestamps]);

  // ==========================================================================
  // DEDUPLICATE & BUILD DUAL-KEY RELATIONSHIP SETS
  // Dedup by pk first, then by username, to prevent stale duplicates
  // ==========================================================================
  const { dedupedFollowers, dedupedFollowing } = useMemo(() => {
    function dedup(list: IGUser[]): IGUser[] {
      const seenPk = new Set<string>();
      const seenName = new Set<string>();
      const result: IGUser[] = [];
      for (const u of list) {
        const pk = String(u.pk).trim();
        const name = u.username.toLowerCase().trim();
        if (seenPk.has(pk) || seenName.has(name)) continue;
        seenPk.add(pk);
        seenName.add(name);
        result.push(u);
      }
      return result;
    }
    return {
      dedupedFollowers: dedup(followers),
      dedupedFollowing: dedup(following),
    };
  }, [followers, following]);

  const relationships = useMemo(() => {
    // Build DUAL-KEY sets: match by pk OR username to prevent mismatches
    const followerPkSet = new Set(dedupedFollowers.map(u => String(u.pk).trim()));
    const followerNameSet = new Set(dedupedFollowers.map(u => u.username.toLowerCase().trim()));
    const followingPkSet = new Set(dedupedFollowing.map(u => String(u.pk).trim()));
    const followingNameSet = new Set(dedupedFollowing.map(u => u.username.toLowerCase().trim()));

    const isFollowerFn = (u: IGUser) =>
      followerPkSet.has(String(u.pk).trim()) || followerNameSet.has(u.username.toLowerCase().trim());
    const isFollowingFn = (u: IGUser) =>
      followingPkSet.has(String(u.pk).trim()) || followingNameSet.has(u.username.toLowerCase().trim());
    const isWhitelistedFn = (u: IGUser) => whitelistPks.has(String(u.pk).trim());

    // Combined followingSet for row-level "isFollowing" checks (used by UserCardRow)
    const followingSet = new Set<string>();
    dedupedFollowing.forEach(u => { followingSet.add(String(u.pk).trim()); });

    const dontFollowBack = dedupedFollowing.filter(u => !isFollowerFn(u) && !isWhitelistedFn(u));
    const fans = dedupedFollowers.filter(u => !isFollowingFn(u) && !isWhitelistedFn(u));
    const mutuals = dedupedFollowing.filter(u => isFollowerFn(u) && !isWhitelistedFn(u));
    const whitelisted = [
      ...dedupedFollowing.filter(u => isWhitelistedFn(u)),
      ...dedupedFollowers.filter(u => isWhitelistedFn(u) && !isFollowingFn(u))
    ];

    return { dontFollowBack, fans, recents: recentActivity, mutuals, whitelisted, followingSet };
  }, [dedupedFollowers, dedupedFollowing, recentActivity, whitelistPks]);

  // Current tab items
  const activeTabList = useMemo(() => {
    switch (activeTab) {
      case 'DONT_FOLLOW_BACK':
        return relationships.dontFollowBack;
      case 'FANS':
        return relationships.fans;
      case 'RECENTS':
        return relationships.recents;
      case 'MUTUALS':
        return relationships.mutuals;
      case 'WHITELISTED':
        return relationships.whitelisted;
      default:
        return [];
    }
  }, [activeTab, relationships]);

  // Search filtering & Sorting
  const displayItems = useMemo(() => {
    let result = activeTabList;
    if (searchQuery.trim().length > 0) {
      const q = searchQuery.trim().toLowerCase();
      result = result.filter(
        (u) => u.username.toLowerCase().includes(q) || u.fullName.toLowerCase().includes(q)
      );
    }

    if (sortOrder === 'AZ') {
      return [...result].sort((a, b) => a.username.localeCompare(b.username));
    } else if (sortOrder === 'ZA') {
      return [...result].sort((a, b) => b.username.localeCompare(a.username));
    }
    return result;
  }, [activeTabList, searchQuery, sortOrder]);

  // ==========================================================================
  // ACTION DISPATCHERS & STATE HANDLERS
  // ==========================================================================
  
  
  const handleSingleAction = useCallback((user: IGUser) => {
    const isCurrentlyFollowing = relationships.followingSet.has(user.pk);
    const actionType = isCurrentlyFollowing ? 'UNFOLLOW' : 'FOLLOW';
    onStartBatchQueue([{ pk: user.pk, username: user.username, action: actionType }]);
  }, [relationships.followingSet, onStartBatchQueue]);

  const toggleCheck = useCallback((pk: string) => {
    setCheckedPks((prev) => {
      const next = new Set(prev);
      if (next.has(pk)) {
        next.delete(pk);
      } else {
        if (next.size >= 25) return prev; // Max 25 guard
        next.add(pk);
      }
      return next;
    });
  }, []);

  const selectAllVisible = useCallback(() => {
    const all = displayItems.map((u) => u.pk);
    setCheckedPks(new Set(all));
  }, [displayItems]);

  const clearChecked = useCallback(() => {
    setCheckedPks(new Set());
  }, []);

  // Single Action Invocation
  
  // ==========================================================================
  // SMART AUTO-QUEUE RUNNER WITH HUMAN PACING GUARD
  // ==========================================================================
  
  const startAutoQueue = useCallback(() => {
    if (checkedPks.size === 0) return;
    const queue = Array.from(checkedPks).map(pk => {
      const u = displayItems.find(x => x.pk === pk);
      const isCurrentlyFollowing = relationships.followingSet.has(pk);
      return { pk, username: u?.username || pk, action: isCurrentlyFollowing ? 'UNFOLLOW' : 'FOLLOW' };
    });
    onStartBatchQueue(queue);
    setCheckedPks(new Set());
  }, [checkedPks, pacingStats.status, displayItems, relationships.followingSet, onStartBatchQueue]);

    // Optional Test Demo Data Loader (Never on mount, only on explicit button tap)
    // Determine row action configuration based on active tab
  const getActionConfig = (tab: TabCategory) => {
    switch (tab) {
      case 'DONT_FOLLOW_BACK':
      case 'RECENTS':
        return { title: 'Unfollow', color: PALETTE.crimson };
      case 'FANS':
        return { title: 'Follow Back', color: PALETTE.emerald };
      case 'MUTUALS':
        return { title: 'Mutual', color: PALETTE.pillActive };
      case 'WHITELISTED':
        return { title: 'Protected', color: PALETTE.igPurple };
    }
  };

  const actionConfig = getActionConfig(activeTab);

  return (
    <SafeAreaView style={styles.safeContainer} edges={['top', 'bottom', 'left', 'right']}>
      <StatusBar barStyle="light-content" backgroundColor={PALETTE.canvas} />
      {showSplash && <CinematicSplash onFinish={() => setShowSplash(false)} />}

      <View style={[styles.headerBar, { paddingTop: Math.max(insets.top + 8, 16), paddingBottom: 12 }]}>
        <View style={styles.headerLeft}>
          <View style={styles.brandTitleRow}>
            <View style={[styles.neonDot, { backgroundColor: '#10B981', width: 10, height: 10, borderRadius: 5, marginRight: 8, shadowColor: '#10B981', shadowOpacity: 0.8, shadowRadius: 6 }]} />
            <Text maxFontSizeMultiplier={1.15} style={[styles.headerBrandText, { fontWeight: '900', fontSize: 24, color: '#FFFFFF', textShadowColor: 'rgba(255,255,255,0.3)', textShadowOffset: {width: 0, height: 2}, textShadowRadius: 4 }]}>InstaPulse</Text>
            <View style={styles.proBadge}>
              <Text maxFontSizeMultiplier={1.15} style={[styles.proBadgeText, { color: '#000', fontWeight: 'bold' }]}>LIVE</Text>
            </View>
          </View>
          <Text maxFontSizeMultiplier={1.15} style={styles.headerSubText}>
            {followers.length} Followers • {following.length} Following
          </Text>
        </View>

        <View style={styles.headerRight}>
          <TouchableOpacity style={styles.headerIconBtn} onPress={() => setShowSplash(true)} activeOpacity={0.7}>
            <Text maxFontSizeMultiplier={1.15} style={styles.headerIconBtnText}>🎬 Intro</Text>
          </TouchableOpacity>
          <TouchableOpacity style={[styles.syncButton, isSyncing && styles.syncButtonActive]} onPress={isLoggedIn ? onStartLiveSync : onOpenLoginModal} disabled={isSyncing} activeOpacity={0.8}>
              {isSyncing ? <ActivityIndicator size="small" color="#FFFFFF" /> : <Text maxFontSizeMultiplier={1.15} style={styles.syncButtonText}>{isLoggedIn ? '⚡ Live Sync' : '🔑 Login'}</Text>}
            </TouchableOpacity>
            {isLoggedIn && (
              <TouchableOpacity style={[styles.syncButton, { marginLeft: 8, backgroundColor: 'rgba(255, 59, 92, 0.1)' }]} onPress={onLogout} activeOpacity={0.8}>
                <Text maxFontSizeMultiplier={1.15} style={[styles.syncButtonText, { color: '#FF3B5C' }]}>Log Out</Text>
              </TouchableOpacity>
            )}
        </View>
      </View>

      <View style={{ paddingHorizontal: 16, paddingBottom: 12, borderBottomWidth: 1, borderBottomColor: 'rgba(255,255,255,0.05)' }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', height: 42, backgroundColor: 'rgba(255,255,255,0.04)', borderRadius: 12, paddingHorizontal: 12, marginBottom: 12 }}>
          <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 16, marginRight: 8, color: '#94A3B8' }}>🔍</Text>
          <TextInput maxFontSizeMultiplier={1.15} style={{ flex: 1, color: '#F8FAFC', fontSize: 14 }} placeholder="Search @username or full name..." placeholderTextColor="#64748B" value={searchQuery} onChangeText={setSearchQuery} autoCapitalize="none" />
          <TouchableOpacity onPress={() => setSortOrder(prev => prev === 'DEFAULT' ? 'AZ' : prev === 'AZ' ? 'ZA' : 'DEFAULT')} style={{ paddingHorizontal: 8 }}>
            <Text maxFontSizeMultiplier={1.15} style={{ color: '#38BDF8', fontSize: 13, fontWeight: '600' }}>{sortOrder === 'DEFAULT' ? 'Sort ↕' : sortOrder === 'AZ' ? 'A-Z ↓' : 'Z-A ↑'}</Text>
          </TouchableOpacity>
        </View>

        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: 'rgba(255,255,255,0.04)', paddingHorizontal: 10, paddingVertical: 6, borderRadius: 12 }}><Text maxFontSizeMultiplier={1.15} style={{ color: '#94A3B8', fontSize: 11, fontWeight: '600' }}>⚡ Uncapped Execution Engine</Text></View>
          <TouchableOpacity style={[{ paddingHorizontal: 12, height: 30, borderRadius: 15, justifyContent: 'center', alignItems: 'center', backgroundColor: 'rgba(255,255,255,0.08)' }, checkedPks.size > 0 && { backgroundColor: '#38BDF8' }]} onPress={checkedPks.size > 0 ? clearChecked : selectAllVisible} activeOpacity={0.7}>
            <Text maxFontSizeMultiplier={1.15} style={[{ color: '#F8FAFC', fontSize: 12, fontWeight: '600' }, checkedPks.size > 0 && { color: '#000' }]}>{checkedPks.size > 0 ? 'Clear Selection' : 'Select All'}</Text>
          </TouchableOpacity>
        </View>
      </View>

      {isSyncing && (
        <View style={styles.syncProgressBar}>
          <ActivityIndicator size="small" color={PALETTE.igPink} />
          <Text maxFontSizeMultiplier={1.15} style={styles.syncProgressText}>Syncing: {syncProgress.followers} Followers • {syncProgress.following} Following...</Text>
        </View>
      )}

      <View style={styles.listContainer}>
        <FlashList<IGUser>
          data={displayItems}
          keyExtractor={(item) => item.pk}
          extraData={`${activeTab}_${following.length}_${followers.length}_${recentActivity.length}_${whitelistPks.size}_${checkedPks.size}`}
          refreshing={isSyncing}
          onRefresh={onStartLiveSync}
          ListHeaderComponent={() => (
            <View style={{ paddingHorizontal: 16, paddingTop: 16, paddingBottom: 8 }}>
              <View style={{ flexDirection: 'row', gap: 10, marginBottom: 10 }}>
                <TouchableOpacity style={[{ width: cardWidth, backgroundColor: '#131724', borderRadius: 18, padding: 16, borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.04)' }, activeTab === 'DONT_FOLLOW_BACK' && { borderColor: '#FF3B5C', backgroundColor: 'rgba(255, 59, 92, 0.05)' }]} onPress={() => setActiveTab('DONT_FOLLOW_BACK')} activeOpacity={0.85}>
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }}>
                    <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 32, fontWeight: '900', color: '#FFF' }}>{relationships.dontFollowBack.length}</Text>
                    <View style={{ backgroundColor: 'rgba(255, 59, 92, 0.15)', paddingHorizontal: 8, paddingVertical: 4, borderRadius: 8 }}><Text maxFontSizeMultiplier={1.15} style={{ fontSize: 10, fontWeight: 'bold', color: '#FF3B5C' }}>TRAITORS</Text></View>
                  </View>
                  <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 13, color: '#94A3B8', fontWeight: '600' }}>Not Following Back</Text>
                  <View style={{ marginTop: 8, height: 4, backgroundColor: 'rgba(255,255,255,0.1)', borderRadius: 2 }}><View style={{ width: `${Math.min(100, (relationships.dontFollowBack.length / Math.max(1, following.length)) * 100)}%`, height: '100%', backgroundColor: '#FF3B5C', borderRadius: 2 }} /></View>
                </TouchableOpacity>

                <TouchableOpacity style={[{ width: cardWidth, backgroundColor: '#131724', borderRadius: 18, padding: 16, borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.04)' }, activeTab === 'FANS' && { borderColor: '#10B981', backgroundColor: 'rgba(16, 185, 129, 0.05)' }]} onPress={() => setActiveTab('FANS')} activeOpacity={0.85}>
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }}>
                    <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 32, fontWeight: '900', color: '#FFF' }}>{relationships.fans.length}</Text>
                    <View style={{ backgroundColor: 'rgba(16, 185, 129, 0.15)', paddingHorizontal: 8, paddingVertical: 4, borderRadius: 8 }}><Text maxFontSizeMultiplier={1.15} style={{ fontSize: 10, fontWeight: 'bold', color: '#10B981' }}>FANS</Text></View>
                  </View>
                  <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 13, color: '#94A3B8', fontWeight: '600' }}>Loyal Followers</Text>
                  <View style={{ marginTop: 8, height: 4, backgroundColor: 'rgba(255,255,255,0.1)', borderRadius: 2 }}><View style={{ width: `${Math.min(100, (relationships.fans.length / Math.max(1, followers.length)) * 100)}%`, height: '100%', backgroundColor: '#10B981', borderRadius: 2 }} /></View>
                </TouchableOpacity>
              </View>

              <View style={{ flexDirection: 'row', gap: 10, marginBottom: 16 }}>
                <TouchableOpacity style={[{ width: cardWidth, backgroundColor: '#131724', borderRadius: 18, padding: 16, borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.04)' }, activeTab === 'RECENTS' && { borderColor: '#38BDF8', backgroundColor: 'rgba(56, 189, 248, 0.05)' }]} onPress={() => setActiveTab('RECENTS')} activeOpacity={0.85}>
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }}>
                    <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 32, fontWeight: '900', color: '#FFF' }}>{relationships.recents.length}</Text>
                    <View style={{ backgroundColor: 'rgba(56, 189, 248, 0.15)', paddingHorizontal: 8, paddingVertical: 4, borderRadius: 8 }}><Text maxFontSizeMultiplier={1.15} style={{ fontSize: 10, fontWeight: 'bold', color: '#38BDF8' }}>RECENTS</Text></View>
                  </View>
                  <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 13, color: '#94A3B8', fontWeight: '600' }}>Action History</Text>
                  <View style={{ marginTop: 8, height: 4, backgroundColor: 'rgba(255,255,255,0.1)', borderRadius: 2 }}><View style={{ width: '100%', height: '100%', backgroundColor: '#38BDF8', borderRadius: 2 }} /></View>
                </TouchableOpacity>

                <TouchableOpacity style={[{ width: cardWidth, backgroundColor: '#131724', borderRadius: 18, padding: 16, borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.04)' }, activeTab === 'MUTUALS' && { borderColor: '#A855F7', backgroundColor: 'rgba(168, 85, 247, 0.05)' }]} onPress={() => setActiveTab('MUTUALS')} activeOpacity={0.85}>
                  <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }}>
                    <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 32, fontWeight: '900', color: '#FFF' }}>{relationships.mutuals.length}</Text>
                    <View style={{ backgroundColor: 'rgba(168, 85, 247, 0.15)', paddingHorizontal: 8, paddingVertical: 4, borderRadius: 8 }}><Text maxFontSizeMultiplier={1.15} style={{ fontSize: 10, fontWeight: 'bold', color: '#A855F7' }}>MUTUALS</Text></View>
                  </View>
                  <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 13, color: '#94A3B8', fontWeight: '600' }}>Mutual Connections</Text>
                  <View style={{ marginTop: 8, height: 4, backgroundColor: 'rgba(255,255,255,0.1)', borderRadius: 2 }}><View style={{ width: `${Math.min(100, (relationships.mutuals.length / Math.max(1, following.length)) * 100)}%`, height: '100%', backgroundColor: '#A855F7', borderRadius: 2 }} /></View>
                </TouchableOpacity>
              </View>
              
              <TouchableOpacity style={[{ backgroundColor: '#131724', borderRadius: 18, padding: 16, borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.04)', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }, activeTab === 'WHITELISTED' && { borderColor: '#38BDF8', backgroundColor: 'rgba(56, 189, 248, 0.05)' }]} onPress={() => setActiveTab('WHITELISTED')} activeOpacity={0.85}>
                <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                  <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 18, marginRight: 8 }}>🛡️</Text>
                  <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 14, color: '#94A3B8', fontWeight: '600' }}>Protected Whitelist</Text>
                </View>
                <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 22, fontWeight: '900', color: '#FFF' }}>{relationships.whitelisted.length}</Text>
              </TouchableOpacity>
            </View>
          )}
          renderItem={({ item }) => (
            <UserCardRow
              user={item}
              isChecked={checkedPks.has(item.pk)}
              isWhitelisted={whitelistPks.has(item.pk)}
              isLoadingAction={busyPk === item.pk}
              isFollowing={relationships.followingSet.has(item.pk)}
              isPacingCooldown={false}
              onToggleCheck={toggleCheck}
              onToggleWhitelist={onToggleWhitelist}
              onExecuteSingle={handleSingleAction}
              onInspect={onInspectProfile}
            />
          )}
          ListEmptyComponent={
            <View style={styles.emptyContainer}>
              <Text maxFontSizeMultiplier={1.15} style={styles.emptyTitle}>No Accounts in this Category</Text>
              <Text maxFontSizeMultiplier={1.15} style={styles.emptySubtitle}>
                {isLoggedIn ? 'Run Live Sync above or switch filters to view relationships.' : 'Log in or load demo data to view relationship diffs.'}
              </Text>
            </View>
          }
          contentContainerStyle={{ paddingBottom: Math.max(insets.bottom + 90, 110) }}
        />
      </View>

      {checkedPks.size > 0 && (
        <View style={[styles.stickyQueueBar, { paddingBottom: Math.max(insets.bottom + 10, 16) }]}>
          <View style={styles.queueInfo}>
            <Text maxFontSizeMultiplier={1.15} style={styles.queueCountText}>{checkedPks.size} Accounts Queued</Text>
            <Text maxFontSizeMultiplier={1.15} style={styles.queueSubText}>2.5s - 4.5s Fast Pacing Active</Text>
          </View>
          <View style={styles.queueControls}>
            <TouchableOpacity style={[styles.startQueueBtn]} onPress={startAutoQueue} activeOpacity={0.8}>
              <Text maxFontSizeMultiplier={1.15} style={styles.startQueueBtnText}>Execute All ({checkedPks.size})</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
    </SafeAreaView>
  );
};

// ============================================================================
// 5. OBSIDIAN STYLESHEET (OPTIMIZED FOR 60/120 FPS)
// ============================================================================
const styles = StyleSheet.create({
  safeContainer: {
    flex: 1,
    backgroundColor: PALETTE.canvas,
  },

  // Splash Screen Overlay
  splashCanvas: {
    position: 'absolute', top: 0, left: 0, right: 0, bottom: 0,
    backgroundColor: PALETTE.canvas,
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 9999,
  },
  radarRing: {
    position: 'absolute',
    borderRadius: 999,
    borderWidth: 2,
  },
  radarRing1: {
    width: 220,
    height: 220,
    borderColor: PALETTE.igPink,
  },
  radarRing2: {
    width: 260,
    height: 260,
    borderColor: PALETTE.igOrange,
  },
  splashCenterContent: {
    alignItems: 'center',
  },
  splashIconBox: {
    width: 72,
    height: 72,
    borderRadius: 24,
    backgroundColor: PALETTE.cardBg,
    borderWidth: 1,
    borderColor: PALETTE.cardBorder,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 16,
  },
  splashLightningIcon: {
    fontSize: 34,
  },
  splashBrandTitle: {
    fontSize: 32,
    fontWeight: '900',
    color: PALETTE.textPrimary,
    letterSpacing: 0.8,
  },
  splashBrandTagline: {
    fontSize: 10,
    fontWeight: '800',
    color: PALETTE.textMuted,
    letterSpacing: 2.2,
    marginTop: 6,
  },
  anamorphicSweep: {
    position: 'absolute',
    bottom: -10,
    width: 140,
    height: 2,
    backgroundColor: PALETTE.igOrange,
    shadowColor: PALETTE.igPink,
    shadowOffset: { width: 0, height: 0 },
    shadowOpacity: 1,
    shadowRadius: 8,
  },

  // Header Bar
  headerBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingTop: 8,
    paddingBottom: 10,
  },
  headerLeft: {
    flex: 1,
  },
  brandTitleRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  neonDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: PALETTE.emerald,
    marginRight: 8,
  },
  headerBrandText: {
    fontSize: 22,
    fontWeight: '900',
    color: PALETTE.textPrimary,
    letterSpacing: 0.2,
  },
  proBadge: {
    backgroundColor: 'rgba(225, 48, 108, 0.2)',
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 6,
    marginLeft: 8,
  },
  proBadgeText: {
    fontSize: 9,
    fontWeight: '900',
    color: PALETTE.igPink,
  },
  headerSubText: {
    fontSize: 11,
    color: PALETTE.textSecondary,
    marginTop: 2,
  },
  headerRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  headerIconBtn: {
    backgroundColor: PALETTE.cardBg,
    borderWidth: 1,
    borderColor: PALETTE.cardBorder,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 10,
  },
  headerIconBtnText: {
    fontSize: 11,
    fontWeight: '700',
    color: PALETTE.textSecondary,
  },
  syncButton: {
    backgroundColor: PALETTE.cardBg,
    borderWidth: 1,
    borderColor: PALETTE.igPink,
    paddingHorizontal: 12,
    paddingVertical: 7,
    borderRadius: 10,
  },
  syncButtonActive: {
    backgroundColor: PALETTE.pillActive,
  },
  syncButtonText: {
    fontSize: 12,
    fontWeight: '800',
    color: PALETTE.igPink,
  },

  // Sync Progress Bar
  syncProgressBar: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1C1F30',
    paddingHorizontal: 16,
    paddingVertical: 8,
    gap: 10,
  },
  syncProgressText: {
    fontSize: 12,
    color: PALETTE.textPrimary,
    fontWeight: '600',
  },

  // Bento Metrics Section
  bentoSection: {
    paddingHorizontal: 14,
    gap: 8,
    marginTop: 4,
  },
  bentoRow: {
    flexDirection: 'row',
    gap: 8,
  },
  bentoCard: {
    flex: 1,
    backgroundColor: PALETTE.cardBg,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: PALETTE.cardBorder,
    padding: 12,
  },
  bentoCardActive: {
    borderColor: PALETTE.igPink,
    backgroundColor: '#181C2B',
  },
  bentoHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  bentoCount: {
    fontSize: 22,
    fontWeight: '900',
    color: PALETTE.textPrimary,
  },
  badgeTag: {
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 6,
  },
  badgeText: {
    fontSize: 9,
    fontWeight: '800',
  },
  bentoTitle: {
    fontSize: 11,
    fontWeight: '700',
    color: PALETTE.textSecondary,
    marginTop: 4,
  },

  // Pill Filter Tabs
  pillTabBar: {
    flexDirection: 'row',
    paddingHorizontal: 14,
    marginTop: 10,
    gap: 6,
  },
  pillBtn: {
    backgroundColor: PALETTE.cardBg,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: PALETTE.cardBorder,
    paddingHorizontal: 12,
    paddingVertical: 7,
  },
  pillBtnActive: {
    backgroundColor: PALETTE.pillActive,
    borderColor: PALETTE.igPink,
  },
  pillBtnText: {
    fontSize: 11,
    color: PALETTE.textSecondary,
    fontWeight: '700',
  },
  pillBtnTextActive: {
    color: PALETTE.textPrimary,
  },

  // Search & Batch Toolbar
  searchToolbar: {
    flexDirection: 'row',
    paddingHorizontal: 14,
    marginTop: 10,
    gap: 8,
  },
  searchInputBox: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: PALETTE.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: PALETTE.cardBorder,
    paddingHorizontal: 10,
    height: 38,
  },
  searchIcon: {
    fontSize: 12,
    marginRight: 6,
  },
  searchInput: {
    flex: 1,
    fontSize: 12,
    color: PALETTE.textPrimary,
  },
  toolbarBtn: {
    backgroundColor: PALETTE.cardBg,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: PALETTE.cardBorder,
    paddingHorizontal: 12,
    justifyContent: 'center',
  },
  toolbarBtnHighlight: {
    borderColor: PALETTE.igPink,
    backgroundColor: PALETTE.pillActive,
  },
  toolbarBtnText: {
    fontSize: 11,
    fontWeight: '700',
    color: PALETTE.textSecondary,
  },

  // Pacing Guard Banner
  pacingBanner: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    marginTop: 8,
    marginBottom: 4,
  },
  pacingStatusRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  pacingIndicator: {
    fontSize: 11,
    marginRight: 6,
  },
  pacingLabel: {
    fontSize: 11,
    fontWeight: '700',
    color: PALETTE.textMuted,
  },
  demoLoadButton: {
    backgroundColor: '#1E2435',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 8,
  },
  demoLoadButtonText: {
    fontSize: 10,
    fontWeight: '700',
    color: PALETTE.cyan,
  },

  // FlashList Container & Cards
  listContainer: {
    flex: 1,
    marginTop: 4,
  },
  listContentPadding: {
    paddingHorizontal: 14,
    paddingBottom: 90,
  },
  rowCard: {
    height: 66,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: PALETTE.cardBg,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: PALETTE.cardBorder,
    paddingHorizontal: 12,
    marginBottom: 8,
  },
  checkboxContainer: {
    width: 22,
    height: 22,
    borderRadius: 6,
    borderWidth: 1.5,
    borderColor: PALETTE.textMuted,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 10,
  },
  checkboxActive: {
    backgroundColor: PALETTE.igPink,
    borderColor: PALETTE.igPink,
  },
  checkmarkIcon: {
    color: '#FFFFFF',
    fontSize: 12,
    fontWeight: '900',
  },
  avatarContainer: {
    width: 44,
    height: 44,
    borderRadius: 22,
    overflow: 'hidden',
    marginRight: 10,
  },
  avatarImage: {
    width: 44,
    height: 44,
  },
  avatarFallback: {
    width: 44,
    height: 44,
    backgroundColor: '#262D42',
    alignItems: 'center',
    justifyContent: 'center',
  },
  avatarFallbackText: {
    fontSize: 18,
    fontWeight: '800',
    color: PALETTE.textPrimary,
  },
  userMetaContainer: {
    flex: 1,
  },
  usernameRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  usernameText: {
    fontSize: 13,
    fontWeight: '800',
    color: PALETTE.textPrimary,
  },
  verifiedBadge: {
    fontSize: 10,
    fontWeight: '900',
    color: PALETTE.cyan,
    marginLeft: 4,
  },
  fullNameText: {
    fontSize: 11,
    color: PALETTE.textSecondary,
    marginTop: 2,
  },
  shieldButton: {
    padding: 6,
    marginRight: 6,
    opacity: 0.35,
  },
  shieldButtonActive: {
    opacity: 1,
  },
  shieldIcon: {
    fontSize: 16,
  },
  actionButton: {
    paddingHorizontal: 14,
    paddingVertical: 7,
    borderRadius: 10,
    minWidth: 86,
    alignItems: 'center',
  },
  actionButtonDisabled: {
    opacity: 0.5,
  },
  actionButtonText: {
    fontSize: 11,
    fontWeight: '800',
    color: '#FFFFFF',
  },

  // Empty State
  emptyContainer: {
    alignItems: 'center',
    paddingVertical: 60,
  },
  emptyTitle: {
    fontSize: 15,
    fontWeight: '800',
    color: PALETTE.textPrimary,
  },
  emptySubtitle: {
    fontSize: 12,
    color: PALETTE.textSecondary,
    textAlign: 'center',
    marginTop: 6,
    paddingHorizontal: 30,
  },

  // Sticky Bottom Queue Bar
  stickyQueueBar: {
    position: 'absolute',
    bottom: 14,
    left: 14,
    right: 14,
    backgroundColor: '#181C2B',
    borderRadius: 16,
    borderWidth: 1,
    borderColor: PALETTE.cardBorder,
    padding: 12,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 8 },
    shadowOpacity: 0.5,
    shadowRadius: 12,
    elevation: 8,
  },
  queueInfo: {
    flex: 1,
  },
  queueCountText: {
    fontSize: 13,
    fontWeight: '800',
    color: PALETTE.textPrimary,
  },
  queueSubText: {
    fontSize: 10,
    color: PALETTE.textSecondary,
    marginTop: 2,
  },
  queueControls: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  fastToggleBtn: {
    backgroundColor: PALETTE.cardBg,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: PALETTE.cardBorder,
    paddingHorizontal: 8,
    paddingVertical: 6,
  },
  fastToggleActive: {
    borderColor: PALETTE.cyan,
  },
  fastToggleText: {
    fontSize: 10,
    fontWeight: '800',
    color: PALETTE.textPrimary,
  },
  startQueueBtn: {
    backgroundColor: PALETTE.crimson,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 10,
  },
  startQueueBtnDisabled: {
    opacity: 0.4,
  },
  startQueueBtnText: {
    fontSize: 12,
    fontWeight: '800',
    color: '#FFFFFF',
  },
  pauseQueueBtn: {
    backgroundColor: PALETTE.amber,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 10,
  },
  pauseQueueBtnText: {
    fontSize: 12,
    fontWeight: '800',
    color: '#000000',
  },
});

export default InstaPulseDashboard;
