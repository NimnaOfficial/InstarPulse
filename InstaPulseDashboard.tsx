import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import { useWindowDimensions, View, Text, StyleSheet, TouchableOpacity, TextInput, Animated, StatusBar, ActivityIndicator, Image, Dimensions } from 'react-native';
import { FlashList } from '@shopify/flash-list';

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
  lastSyncTime?: number;
  onOpenLoginModal: () => void;
  onStartLiveSync: () => void;
  onLogout: () => void;
  onStartBatchQueue: (queue: any[]) => void;
  onInspectProfile: (username: string, pk?: string) => void;
  onToggleWhitelist: (pk: string) => void;
}

export type TabCategory = 'DONT_FOLLOW_BACK' | 'FANS' | 'RECENTS' | 'MUTUALS' | 'WHITELISTED';
export type SortOrder = 'DEFAULT' | 'AZ' | 'ZA' | 'AGE_NEW' | 'AGE_OLD';
export type SubFilter = 'ALL' | 'VERIFIED' | 'PRIVATE' | 'PUBLIC';

const PALETTE = {
  canvas: '#090A0F',
  cardBg: '#12151F',
  cardBorder: 'rgba(255, 255, 255, 0.08)',
  textPrimary: '#FFFFFF',
  textSecondary: '#8E95A5',
  textMuted: '#52596B',
  igPink: '#DD2A7B',
  crimson: '#FF3B5C',
  emerald: '#10B981',
  cyan: '#38BDF8',
  amber: '#F59E0B',
  purple: '#A855F7'
};

interface UserRowProps {
  user: IGUser;
  isChecked: boolean;
  isWhitelisted: boolean;
  isLoadingAction: boolean;
  isFollowing: boolean;
  onToggleCheck: (pk: string) => void;
  onToggleWhitelist: (pk: string) => void;
  onExecuteSingle: (user: IGUser) => void;
  onInspect: (username: string, pk: string) => void;
}

const UserCardRow = React.memo(function UserCardRow({ user, isChecked, isWhitelisted, isLoadingAction, isFollowing, onToggleCheck, onToggleWhitelist, onExecuteSingle, onInspect }: UserRowProps) {
  const isRecents = user.lastAction !== undefined;
  const actionTitle = isFollowing ? 'Unfollow' : 'Follow';
  const actionColor = isFollowing ? PALETTE.crimson : PALETTE.emerald;
  let neonBorder = 'rgba(255,255,255,0.07)';
  if (!isFollowing && !isRecents) neonBorder = PALETTE.crimson;
  if (isFollowing && !isRecents) neonBorder = PALETTE.emerald;
  if (isRecents) neonBorder = PALETTE.cyan;

  const pulseAnim = useMemo(() => new Animated.Value(0), []);
  useEffect(() => {
    if (isLoadingAction) {
      Animated.loop(Animated.sequence([
        Animated.timing(pulseAnim, { toValue: 1, duration: 600, useNativeDriver: false }),
        Animated.timing(pulseAnim, { toValue: 0, duration: 600, useNativeDriver: false })
      ])).start();
    } else {
      pulseAnim.setValue(0);
    }
  }, [isLoadingAction, pulseAnim]);

  const animatedBorder = pulseAnim.interpolate({
    inputRange: [0, 1],
    outputRange: ['rgba(255,255,255,0.05)', actionColor]
  });

  return (
    <Animated.View style={[styles.cardRow, {
      borderColor: isLoadingAction ? animatedBorder : 'rgba(255,255,255,0.05)',
      borderLeftColor: isLoadingAction ? actionColor : neonBorder,
      transform: [{ scale: isLoadingAction ? 0.98 : 1 }]
    }]}>
      <TouchableOpacity onPress={() => onToggleCheck(user.pk)} style={[styles.checkBtn, isChecked && styles.checkBtnActive]} activeOpacity={0.8}>
        {isChecked && <Text maxFontSizeMultiplier={1.15} style={styles.checkBtnText}>✓</Text>}
      </TouchableOpacity>
      <TouchableOpacity onPress={() => onInspect(user.username, user.pk)} style={[styles.avatarContainer, { borderColor: neonBorder }]}>
        {user.profilePicUrl ? <Image source={{uri: user.profilePicUrl}} style={styles.avatarImg} /> : <View style={styles.avatarFallback}><Text maxFontSizeMultiplier={1.15} style={styles.avatarFallbackText}>{user.username.charAt(0).toUpperCase()}</Text></View>}
      </TouchableOpacity>
      <TouchableOpacity style={styles.userInfo} onPress={() => onInspect(user.username, user.pk)} activeOpacity={0.8}>
        <View style={styles.usernameRow}>
          <Text maxFontSizeMultiplier={1.15} style={styles.usernameText} numberOfLines={1}>@{user.username}</Text>
          {user.isVerified && <Text maxFontSizeMultiplier={1.15} style={styles.verifiedIcon}>✓</Text>}
          {user.isPrivate && <Text maxFontSizeMultiplier={1.15} style={styles.privateIcon}>🔒</Text>}
        </View>
        <Text maxFontSizeMultiplier={1.15} style={styles.fullNameText} numberOfLines={1}>{user.fullName || 'Instagram Account'}</Text>
        {isRecents && <Text maxFontSizeMultiplier={1.15} style={[styles.recentText, { color: user.lastAction === 'unfollowed' ? PALETTE.amber : user.lastAction === 'skipped_unavailable' ? PALETTE.textSecondary : PALETTE.emerald }]}>{user.lastAction === 'unfollowed' ? 'Unfollowed just now' : user.lastAction === 'skipped_unavailable' ? 'Unavailable / Skipped' : 'Followed just now'}</Text>}
      </TouchableOpacity>
      <TouchableOpacity style={[styles.whitelistBtn, isWhitelisted && styles.whitelistBtnActive]} onPress={() => onToggleWhitelist(user.pk)} activeOpacity={0.7}>
        <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 16 }}>{isWhitelisted ? '🛡️' : '🛡'}</Text>
      </TouchableOpacity>
      <TouchableOpacity style={[styles.actionBtn, { backgroundColor: actionColor }, isLoadingAction && { opacity: 0.5 }]} disabled={isLoadingAction} onPress={() => onExecuteSingle(user)} activeOpacity={0.8}>
        {isLoadingAction ? (
          <View style={{ flexDirection: 'row', alignItems: 'center' }}>
            <ActivityIndicator size="small" color="#FFFFFF" style={{ marginRight: 6 }} />
            <Text maxFontSizeMultiplier={1.15} style={styles.actionBtnText}>⚡ {isFollowing ? 'Unfollowing...' : 'Following...'}</Text>
          </View>
        ) : (
          <Text maxFontSizeMultiplier={1.15} style={styles.actionBtnText}>{actionTitle}</Text>
        )}
      </TouchableOpacity>
    </Animated.View>
  );
});

export const InstaPulseDashboard: React.FC<BridgeAdapterProps> = ({
  isLoggedIn,
  isSyncing,
  syncProgress,
  followers,
  following,
  recentActivity,
  whitelistPks,
  busyPk,
  lastSyncTime,
  onToggleWhitelist,
  onOpenLoginModal,
  onStartLiveSync,
  onLogout,
  onStartBatchQueue,
  onInspectProfile,
}) => {
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const cardWidth = (width - 36) / 2;

  const [activeTab, setActiveTab] = useState<TabCategory>('DONT_FOLLOW_BACK');
  const [searchQuery, setSearchQuery] = useState('');
  const [sortOrder, setSortOrder] = useState<SortOrder>('DEFAULT');
  const [subFilter, setSubFilter] = useState<SubFilter>('ALL');
  const [checkedPks, setCheckedPks] = useState<Set<string>>(new Set());

  const [netDelta, setNetDelta] = useState(0);
  const [now, setNow] = useState(() => Date.now());

  const prevFollowersCountRef = useRef(followers.length);
  useEffect(() => {
    if (followers.length > 0 && !isSyncing) {
       setNetDelta(followers.length - prevFollowersCountRef.current);
       prevFollowersCountRef.current = followers.length;
    }
  }, [followers.length, isSyncing]);

  useEffect(() => {
    const interval = setInterval(() => setNow(Date.now()), 60000);
    return () => clearInterval(interval);
  }, []);

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
    return { dedupedFollowers: dedup(followers), dedupedFollowing: dedup(following) };
  }, [followers, following]);

  const relationships = useMemo(() => {
    const followerPkSet = new Set(dedupedFollowers.map(u => String(u.pk).trim()));
    const followerNameSet = new Set(dedupedFollowers.map(u => u.username.toLowerCase().trim()));
    const followingPkSet = new Set(dedupedFollowing.map(u => String(u.pk).trim()));
    const followingNameSet = new Set(dedupedFollowing.map(u => u.username.toLowerCase().trim()));

    const isFollowerFn = (u: IGUser) => followerPkSet.has(String(u.pk).trim()) || followerNameSet.has(u.username.toLowerCase().trim());
    const isFollowingFn = (u: IGUser) => followingPkSet.has(String(u.pk).trim()) || followingNameSet.has(u.username.toLowerCase().trim());
    const isWhitelistedFn = (u: IGUser) => whitelistPks.has(String(u.pk).trim());

    const followingSet = new Set<string>();
    dedupedFollowing.forEach(u => followingSet.add(String(u.pk).trim()));

    const dontFollowBack = dedupedFollowing.filter(u => !isFollowerFn(u) && !isWhitelistedFn(u));
    const fans = dedupedFollowers.filter(u => !isFollowingFn(u) && !isWhitelistedFn(u));
    const mutuals = dedupedFollowing.filter(u => isFollowerFn(u) && !isWhitelistedFn(u));
    const whitelisted = [
      ...dedupedFollowing.filter(u => isWhitelistedFn(u)),
      ...dedupedFollowers.filter(u => isWhitelistedFn(u) && !isFollowingFn(u))
    ];

    return { dontFollowBack, fans, recents: recentActivity, mutuals, whitelisted, followingSet };
  }, [dedupedFollowers, dedupedFollowing, recentActivity, whitelistPks]);

  const activeTabList = useMemo(() => {
    switch (activeTab) {
      case 'DONT_FOLLOW_BACK': return relationships.dontFollowBack;
      case 'FANS': return relationships.fans;
      case 'RECENTS': return relationships.recents;
      case 'MUTUALS': return relationships.mutuals;
      case 'WHITELISTED': return relationships.whitelisted;
      default: return [];
    }
  }, [activeTab, relationships]);

  const displayItems = useMemo(() => {
    let result = activeTabList;
    
    if (searchQuery.trim().length > 0) {
      const q = searchQuery.trim().toLowerCase();
      result = result.filter(u => u.username.toLowerCase().includes(q) || u.fullName.toLowerCase().includes(q));
    }

    if (subFilter === 'VERIFIED') result = result.filter(u => u.isVerified);
    if (subFilter === 'PRIVATE') result = result.filter(u => u.isPrivate);
    if (subFilter === 'PUBLIC') result = result.filter(u => !u.isPrivate);

    if (sortOrder === 'AZ') {
      result = [...result].sort((a, b) => a.username.localeCompare(b.username));
    } else if (sortOrder === 'ZA') {
      result = [...result].sort((a, b) => b.username.localeCompare(a.username));
    } else if (sortOrder === 'AGE_NEW') {
      result = [...result].sort((a, b) => (Number(b.pk) || 0) - (Number(a.pk) || 0));
    } else if (sortOrder === 'AGE_OLD') {
      result = [...result].sort((a, b) => (Number(a.pk) || 0) - (Number(b.pk) || 0));
    }

    return result;
  }, [activeTabList, searchQuery, sortOrder, subFilter]);

  const handleSingleAction = useCallback((user: IGUser) => {
    const isCurrentlyFollowing = relationships.followingSet.has(user.pk);
    onStartBatchQueue([{ pk: user.pk, username: user.username, action: isCurrentlyFollowing ? 'UNFOLLOW' : 'FOLLOW' }]);
  }, [relationships.followingSet, onStartBatchQueue]);

  const toggleCheck = useCallback((pk: string) => {
    setCheckedPks(prev => {
      const next = new Set(prev);
      if (next.has(pk)) next.delete(pk);
      else { if (next.size >= 50) return prev; next.add(pk); }
      return next;
    });
  }, []);

  const selectAllVisible = useCallback(() => setCheckedPks(new Set(displayItems.map(u => u.pk))), [displayItems]);
  const clearChecked = useCallback(() => setCheckedPks(new Set()), []);

  const startAutoQueue = useCallback(() => {
    if (checkedPks.size === 0) return;
    const queue = Array.from(checkedPks).map(pk => {
      const u = displayItems.find(x => x.pk === pk);
      const isCurrentlyFollowing = relationships.followingSet.has(pk);
      return { pk, username: u?.username || pk, action: isCurrentlyFollowing ? 'UNFOLLOW' : 'FOLLOW' };
    });
    onStartBatchQueue(queue);
    setCheckedPks(new Set());
  }, [checkedPks, displayItems, relationships.followingSet, onStartBatchQueue]);

  const reciprocityRatio = following.length ? ((relationships.mutuals.length / following.length) * 100).toFixed(1) : '0.0';
  const followerRatio = following.length ? (followers.length / following.length).toFixed(2) : '0.00';
  const netDeltaStr = netDelta > 0 ? `+${netDelta}` : `${netDelta}`;

  const syncStatusText = isSyncing 
    ? "⚡ Auto-Syncing in background..." 
    : lastSyncTime 
      ? `🟢 Live Synced • ${Math.floor((now - lastSyncTime) / 60000)}m ago` 
      : "🟢 Live Synced";

  return (
    <SafeAreaView style={styles.safeContainer} edges={['top', 'bottom', 'left', 'right']}>
      <StatusBar barStyle="light-content" backgroundColor={PALETTE.canvas} />

      <View style={[styles.headerBar, { paddingTop: Math.max(insets.top + 8, 16), paddingBottom: 12 }]}>
        <View style={styles.headerLeft}>
          <View style={styles.brandTitleRow}>
            <View style={[styles.neonDot, { backgroundColor: isSyncing ? PALETTE.cyan : PALETTE.emerald, shadowColor: isSyncing ? PALETTE.cyan : PALETTE.emerald }]} />
            <Text maxFontSizeMultiplier={1.15} style={styles.headerBrandText}>InstaPulse</Text>
            <View style={styles.proBadge}><Text maxFontSizeMultiplier={1.15} style={styles.proBadgeText}>LIVE</Text></View>
          </View>
          <Text maxFontSizeMultiplier={1.15} style={styles.headerSubText}>{syncStatusText}</Text>
        </View>

        <View style={styles.headerRight}>
          <TouchableOpacity style={[styles.syncButton, isSyncing && styles.syncButtonActive]} onPress={isLoggedIn ? onStartLiveSync : onOpenLoginModal} disabled={isSyncing} activeOpacity={0.8}>
            {isSyncing ? <ActivityIndicator size="small" color="#FFFFFF" /> : <Text maxFontSizeMultiplier={1.15} style={styles.syncButtonText}>{isLoggedIn ? '↻ Sync' : '🔑 Login'}</Text>}
          </TouchableOpacity>
          {isLoggedIn && (
            <TouchableOpacity style={[styles.syncButton, { marginLeft: 8, backgroundColor: 'rgba(255, 59, 92, 0.1)', borderColor: 'rgba(255, 59, 92, 0.2)' }]} onPress={onLogout} activeOpacity={0.8}>
              <Text maxFontSizeMultiplier={1.15} style={[styles.syncButtonText, { color: PALETTE.crimson }]}>Log Out</Text>
            </TouchableOpacity>
          )}
        </View>
      </View>

      <View style={{ paddingHorizontal: 16, paddingBottom: 12 }}>
        <View style={styles.searchRow}>
          <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 16, marginRight: 8, color: PALETTE.textSecondary }}>🔍</Text>
          <TextInput maxFontSizeMultiplier={1.15} style={styles.searchInput} placeholder="Search @username or name..." placeholderTextColor={PALETTE.textMuted} value={searchQuery} onChangeText={setSearchQuery} autoCapitalize="none" />
        </View>
        <View style={styles.filtersRow}>
          <View style={styles.quickChips}>
            {(['ALL', 'VERIFIED', 'PRIVATE', 'PUBLIC'] as SubFilter[]).map(f => (
              <TouchableOpacity key={f} style={[styles.chip, subFilter === f && styles.chipActive]} onPress={() => setSubFilter(f)}>
                <Text style={[styles.chipText, subFilter === f && styles.chipTextActive]}>
                  {f === 'VERIFIED' ? 'Verified ✓' : f === 'PRIVATE' ? 'Private 🔒' : f === 'PUBLIC' ? 'Public 🌐' : 'All'}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
          <TouchableOpacity onPress={() => {
            const cycle = ['DEFAULT', 'AZ', 'ZA', 'AGE_NEW', 'AGE_OLD'];
            setSortOrder(cycle[(cycle.indexOf(sortOrder) + 1) % cycle.length] as SortOrder);
          }} style={styles.sortBtn}>
            <Text style={styles.sortBtnText}>
              {sortOrder === 'DEFAULT' ? 'Sort ↕' : sortOrder === 'AZ' ? 'A-Z ↓' : sortOrder === 'ZA' ? 'Z-A ↑' : sortOrder === 'AGE_NEW' ? 'Newest ★' : 'Oldest ⏳'}
            </Text>
          </TouchableOpacity>
        </View>
        <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 12 }}>
          <View style={{ flexDirection: 'row', alignItems: 'center', backgroundColor: 'rgba(255,255,255,0.04)', paddingHorizontal: 10, paddingVertical: 6, borderRadius: 12 }}>
            <Text maxFontSizeMultiplier={1.15} style={{ color: PALETTE.textSecondary, fontSize: 11, fontWeight: '600' }}>⚡ Uncapped Engine</Text>
          </View>
          <TouchableOpacity style={[styles.selectAllBtn, checkedPks.size > 0 && { backgroundColor: PALETTE.cyan }]} onPress={checkedPks.size > 0 ? clearChecked : selectAllVisible} activeOpacity={0.7}>
            <Text maxFontSizeMultiplier={1.15} style={[styles.selectAllText, checkedPks.size > 0 && { color: '#000' }]}>{checkedPks.size > 0 ? 'Clear Selection' : 'Select All'}</Text>
          </TouchableOpacity>
        </View>
      </View>

      <View style={styles.listContainer}>
        <FlashList<IGUser>
          data={displayItems}
          keyExtractor={(item) => item.pk}
          extraData={`${activeTab}_${following.length}_${followers.length}_${recentActivity.length}_${whitelistPks.size}_${checkedPks.size}_${subFilter}_${sortOrder}`}
          ListHeaderComponent={() => (
            <View style={{ paddingHorizontal: 16, paddingTop: 12, paddingBottom: 8 }}>
              {/* Telemetry Strip */}
              <View style={styles.telemetryStrip}>
                 <View style={styles.telemetryItem}>
                    <Text style={styles.telemetryLabel}>Reciprocity</Text>
                    <Text style={styles.telemetryValue}>{reciprocityRatio}%</Text>
                 </View>
                 <View style={styles.telemetryDivider} />
                 <View style={styles.telemetryItem}>
                    <Text style={styles.telemetryLabel}>Follower Ratio</Text>
                    <Text style={styles.telemetryValue}>{followerRatio}x</Text>
                 </View>
                 <View style={styles.telemetryDivider} />
                 <View style={styles.telemetryItem}>
                    <Text style={styles.telemetryLabel}>Net Delta</Text>
                    <Text style={[styles.telemetryValue, { color: netDelta > 0 ? PALETTE.emerald : netDelta < 0 ? PALETTE.crimson : PALETTE.textPrimary }]}>{netDeltaStr}</Text>
                 </View>
              </View>

              <View style={{ flexDirection: 'row', gap: 10, marginBottom: 10 }}>
                <TouchableOpacity style={[styles.bentoCard, { width: cardWidth }, activeTab === 'DONT_FOLLOW_BACK' && { borderColor: PALETTE.crimson, backgroundColor: 'rgba(255, 59, 92, 0.05)' }]} onPress={() => setActiveTab('DONT_FOLLOW_BACK')} activeOpacity={0.85}>
                  <View style={styles.bentoHeader}>
                    <Text maxFontSizeMultiplier={1.15} style={styles.bentoCount}>{relationships.dontFollowBack.length}</Text>
                    <View style={[styles.badgeTag, { backgroundColor: 'rgba(255, 59, 92, 0.15)' }]}><Text maxFontSizeMultiplier={1.15} style={[styles.badgeText, { color: PALETTE.crimson }]}>TRAITORS</Text></View>
                  </View>
                  <Text maxFontSizeMultiplier={1.15} style={styles.bentoTitle}>Not Following Back</Text>
                  <View style={styles.progressBarBg}><View style={[styles.progressBarFill, { width: `${Math.min(100, (relationships.dontFollowBack.length / Math.max(1, following.length)) * 100)}%`, backgroundColor: PALETTE.crimson }]} /></View>
                </TouchableOpacity>

                <TouchableOpacity style={[styles.bentoCard, { width: cardWidth }, activeTab === 'FANS' && { borderColor: PALETTE.emerald, backgroundColor: 'rgba(16, 185, 129, 0.05)' }]} onPress={() => setActiveTab('FANS')} activeOpacity={0.85}>
                  <View style={styles.bentoHeader}>
                    <Text maxFontSizeMultiplier={1.15} style={styles.bentoCount}>{relationships.fans.length}</Text>
                    <View style={[styles.badgeTag, { backgroundColor: 'rgba(16, 185, 129, 0.15)' }]}><Text maxFontSizeMultiplier={1.15} style={[styles.badgeText, { color: PALETTE.emerald }]}>FANS</Text></View>
                  </View>
                  <Text maxFontSizeMultiplier={1.15} style={styles.bentoTitle}>Loyal Followers</Text>
                  <View style={styles.progressBarBg}><View style={[styles.progressBarFill, { width: `${Math.min(100, (relationships.fans.length / Math.max(1, followers.length)) * 100)}%`, backgroundColor: PALETTE.emerald }]} /></View>
                </TouchableOpacity>
              </View>

              <View style={{ flexDirection: 'row', gap: 10, marginBottom: 16 }}>
                <TouchableOpacity style={[styles.bentoCard, { width: cardWidth }, activeTab === 'RECENTS' && { borderColor: PALETTE.cyan, backgroundColor: 'rgba(56, 189, 248, 0.05)' }]} onPress={() => setActiveTab('RECENTS')} activeOpacity={0.85}>
                  <View style={styles.bentoHeader}>
                    <Text maxFontSizeMultiplier={1.15} style={styles.bentoCount}>{relationships.recents.length}</Text>
                    <View style={[styles.badgeTag, { backgroundColor: 'rgba(56, 189, 248, 0.15)' }]}><Text maxFontSizeMultiplier={1.15} style={[styles.badgeText, { color: PALETTE.cyan }]}>RECENTS</Text></View>
                  </View>
                  <Text maxFontSizeMultiplier={1.15} style={styles.bentoTitle}>Action History</Text>
                  <View style={styles.progressBarBg}><View style={[styles.progressBarFill, { width: '100%', backgroundColor: PALETTE.cyan }]} /></View>
                </TouchableOpacity>

                <TouchableOpacity style={[styles.bentoCard, { width: cardWidth }, activeTab === 'MUTUALS' && { borderColor: PALETTE.purple, backgroundColor: 'rgba(168, 85, 247, 0.05)' }]} onPress={() => setActiveTab('MUTUALS')} activeOpacity={0.85}>
                  <View style={styles.bentoHeader}>
                    <Text maxFontSizeMultiplier={1.15} style={styles.bentoCount}>{relationships.mutuals.length}</Text>
                    <View style={[styles.badgeTag, { backgroundColor: 'rgba(168, 85, 247, 0.15)' }]}><Text maxFontSizeMultiplier={1.15} style={[styles.badgeText, { color: PALETTE.purple }]}>MUTUALS</Text></View>
                  </View>
                  <Text maxFontSizeMultiplier={1.15} style={styles.bentoTitle}>Mutual Connections</Text>
                  <View style={styles.progressBarBg}><View style={[styles.progressBarFill, { width: `${Math.min(100, (relationships.mutuals.length / Math.max(1, following.length)) * 100)}%`, backgroundColor: PALETTE.purple }]} /></View>
                </TouchableOpacity>
              </View>
              
              <TouchableOpacity style={[styles.whitelistTabBtn, activeTab === 'WHITELISTED' && { borderColor: PALETTE.cyan, backgroundColor: 'rgba(56, 189, 248, 0.05)' }]} onPress={() => setActiveTab('WHITELISTED')} activeOpacity={0.85}>
                <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                  <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 18, marginRight: 8 }}>🛡️</Text>
                  <Text maxFontSizeMultiplier={1.15} style={{ fontSize: 14, color: PALETTE.textSecondary, fontWeight: '600' }}>Protected Whitelist</Text>
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
              onToggleCheck={toggleCheck}
              onToggleWhitelist={onToggleWhitelist}
              onExecuteSingle={handleSingleAction}
              onInspect={onInspectProfile}
            />
          )}
          ListEmptyComponent={
            <View style={styles.emptyContainer}>
              <Text maxFontSizeMultiplier={1.15} style={styles.emptyTitle}>No Accounts in this Category</Text>
              <Text maxFontSizeMultiplier={1.15} style={styles.emptySubtitle}>{isLoggedIn ? 'Ensure background sync has completed.' : 'Log in to sync your graph.'}</Text>
            </View>
          }
          contentContainerStyle={{ paddingBottom: Math.max(insets.bottom + 90, 110) }}
        />
      </View>

      {checkedPks.size > 0 && (
        <View style={[styles.stickyQueueBar, { paddingBottom: Math.max(insets.bottom + 10, 16) }]}>
          <View style={styles.queueInfo}>
            <Text maxFontSizeMultiplier={1.15} style={styles.queueCountText}>{checkedPks.size} Accounts Queued</Text>
            <Text maxFontSizeMultiplier={1.15} style={styles.queueSubText}>Fast Execution Active</Text>
          </View>
          <View style={styles.queueControls}>
            <TouchableOpacity style={styles.startQueueBtn} onPress={startAutoQueue} activeOpacity={0.8}>
              <Text maxFontSizeMultiplier={1.15} style={styles.startQueueBtnText}>Execute All ({checkedPks.size})</Text>
            </TouchableOpacity>
          </View>
        </View>
      )}
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  safeContainer: { flex: 1, backgroundColor: PALETTE.canvas },
  headerBar: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 16 },
  headerLeft: { flex: 1 },
  brandTitleRow: { flexDirection: 'row', alignItems: 'center' },
  neonDot: { width: 8, height: 8, borderRadius: 4, marginRight: 8, shadowOpacity: 0.8, shadowRadius: 6 },
  headerBrandText: { fontSize: 22, fontWeight: '900', color: PALETTE.textPrimary, letterSpacing: 0.2 },
  proBadge: { backgroundColor: 'rgba(225, 48, 108, 0.2)', paddingHorizontal: 6, paddingVertical: 2, borderRadius: 6, marginLeft: 8 },
  proBadgeText: { fontSize: 9, fontWeight: '900', color: PALETTE.igPink },
  headerSubText: { fontSize: 11, color: PALETTE.textSecondary, marginTop: 4 },
  headerRight: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  syncButton: { backgroundColor: PALETTE.cardBg, borderWidth: 1, borderColor: PALETTE.igPink, paddingHorizontal: 12, paddingVertical: 7, borderRadius: 10 },
  syncButtonActive: { backgroundColor: 'rgba(225, 48, 108, 0.2)' },
  syncButtonText: { fontSize: 12, fontWeight: '800', color: PALETTE.igPink },
  searchRow: { flexDirection: 'row', alignItems: 'center', height: 42, backgroundColor: 'rgba(255,255,255,0.04)', borderRadius: 12, paddingHorizontal: 12, marginBottom: 12 },
  searchInput: { flex: 1, color: '#F8FAFC', fontSize: 14 },
  filtersRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  quickChips: { flexDirection: 'row', gap: 6 },
  chip: { paddingHorizontal: 10, paddingVertical: 6, borderRadius: 12, backgroundColor: 'rgba(255,255,255,0.05)', borderWidth: 1, borderColor: 'transparent' },
  chipActive: { backgroundColor: 'rgba(56, 189, 248, 0.1)', borderColor: PALETTE.cyan },
  chipText: { fontSize: 11, color: PALETTE.textSecondary, fontWeight: '600' },
  chipTextActive: { color: PALETTE.cyan },
  sortBtn: { paddingHorizontal: 10, paddingVertical: 6 },
  sortBtnText: { color: PALETTE.cyan, fontSize: 12, fontWeight: '700' },
  selectAllBtn: { paddingHorizontal: 12, height: 30, borderRadius: 15, justifyContent: 'center', alignItems: 'center', backgroundColor: 'rgba(255,255,255,0.08)' },
  selectAllText: { color: '#F8FAFC', fontSize: 12, fontWeight: '600' },
  listContainer: { flex: 1 },
  telemetryStrip: { flexDirection: 'row', justifyContent: 'space-around', alignItems: 'center', backgroundColor: PALETTE.cardBg, borderRadius: 16, paddingVertical: 14, paddingHorizontal: 16, marginBottom: 16, borderWidth: 1, borderColor: PALETTE.cardBorder },
  telemetryItem: { alignItems: 'center' },
  telemetryLabel: { fontSize: 10, color: PALETTE.textSecondary, fontWeight: '700', marginBottom: 4, textTransform: 'uppercase', letterSpacing: 0.5 },
  telemetryValue: { fontSize: 16, color: PALETTE.textPrimary, fontWeight: '900' },
  telemetryDivider: { width: 1, height: 24, backgroundColor: 'rgba(255,255,255,0.1)' },
  bentoCard: { backgroundColor: PALETTE.cardBg, borderRadius: 16, borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.04)', padding: 16 },
  bentoHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 },
  bentoCount: { fontSize: 32, fontWeight: '900', color: '#FFF' },
  badgeTag: { paddingHorizontal: 8, paddingVertical: 4, borderRadius: 8 },
  badgeText: { fontSize: 10, fontWeight: 'bold' },
  bentoTitle: { fontSize: 13, color: PALETTE.textSecondary, fontWeight: '600' },
  progressBarBg: { marginTop: 8, height: 4, backgroundColor: 'rgba(255,255,255,0.1)', borderRadius: 2 },
  progressBarFill: { height: '100%', borderRadius: 2 },
  whitelistTabBtn: { backgroundColor: PALETTE.cardBg, borderRadius: 18, padding: 16, borderWidth: 1.5, borderColor: 'rgba(255,255,255,0.04)', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  cardRow: { minHeight: 74, marginHorizontal: 14, marginBottom: 8, paddingVertical: 12, paddingHorizontal: 14, borderRadius: 18, backgroundColor: '#131724', borderWidth: 1.5, borderLeftWidth: 3, flexDirection: 'row', alignItems: 'center' },
  checkBtn: { width: 22, height: 22, borderRadius: 6, marginRight: 12, borderWidth: 1, borderColor: 'rgba(255,255,255,0.2)' },
  checkBtnActive: { backgroundColor: PALETTE.cyan, borderColor: PALETTE.cyan, justifyContent: 'center', alignItems: 'center' },
  checkBtnText: { color: '#000', fontWeight: 'bold', fontSize: 13 },
  avatarContainer: { width: 46, height: 46, borderRadius: 23, marginRight: 14, padding: 2, backgroundColor: 'transparent', borderWidth: 1.5, overflow: 'hidden' },
  avatarImg: { width: '100%', height: '100%', borderRadius: 23 },
  avatarFallback: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: '#262C40', borderRadius: 23 },
  avatarFallbackText: { color: '#F8FAFC', fontSize: 16 },
  userInfo: { flex: 1, flexShrink: 1, marginRight: 8 },
  usernameRow: { flexDirection: 'row', alignItems: 'center' },
  usernameText: { fontSize: 15, fontWeight: '700', color: '#F8FAFC' },
  verifiedIcon: { color: PALETTE.cyan, fontSize: 13, marginLeft: 4 },
  privateIcon: { fontSize: 11, marginLeft: 6 },
  fullNameText: { fontSize: 13, color: PALETTE.textSecondary, marginTop: 2 },
  recentText: { fontSize: 11, marginTop: 2 },
  whitelistBtn: { width: 36, height: 36, borderRadius: 12, backgroundColor: 'rgba(255,255,255,0.04)', alignItems: 'center', justifyContent: 'center', marginRight: 10, flexShrink: 0 },
  whitelistBtnActive: { backgroundColor: 'rgba(255,255,255,0.15)' },
  actionBtn: { height: 36, paddingHorizontal: 16, borderRadius: 12, justifyContent: 'center', alignItems: 'center', minWidth: 90, flexShrink: 0 },
  actionBtnText: { color: '#FFFFFF', fontWeight: 'bold', fontSize: 13 },
  emptyContainer: { alignItems: 'center', justifyContent: 'center', paddingVertical: 60, paddingHorizontal: 30 },
  emptyTitle: { color: '#F8FAFC', fontSize: 18, fontWeight: '700', marginBottom: 8 },
  emptySubtitle: { color: PALETTE.textSecondary, fontSize: 14, textAlign: 'center', lineHeight: 20 },
  stickyQueueBar: { position: 'absolute', bottom: 0, left: 0, right: 0, backgroundColor: 'rgba(19, 23, 36, 0.95)', borderTopWidth: 1, borderTopColor: 'rgba(255,255,255,0.1)', paddingHorizontal: 20, paddingTop: 16, flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', shadowColor: '#000', shadowOffset: { width: 0, height: -10 }, shadowOpacity: 0.3, shadowRadius: 20, elevation: 20 },
  queueInfo: { flex: 1 },
  queueCountText: { color: '#FFF', fontSize: 15, fontWeight: '800' },
  queueSubText: { color: PALETTE.cyan, fontSize: 12, fontWeight: '600', marginTop: 2 },
  queueControls: { flexDirection: 'row', gap: 12 },
  startQueueBtn: { backgroundColor: PALETTE.cyan, paddingHorizontal: 20, paddingVertical: 12, borderRadius: 14 },
  startQueueBtnText: { color: '#000', fontWeight: '800', fontSize: 14 }
});
