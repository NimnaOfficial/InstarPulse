import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
    Text,
  View,
  TouchableOpacity,
  Modal,
  Alert,
  LayoutAnimation,
  StatusBar,
  LogBox
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { WebView, WebViewMessageEvent } from 'react-native-webview';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { InstaPulseDashboard } from './InstaPulseDashboard';
import { InstaActionExecutor, ActionQueueItem } from './InstaActionExecutor';
import { IGUser } from './types';

LogBox.ignoreLogs(['SafeAreaView has been deprecated']);

const STORAGE_KEYS = {
  RECENT_ACTIVITY: '@instapulse_recent_activity_v1',
  LAST_FOLLOWERS: '@instapulse_last_followers_v1',
  LAST_FOLLOWING: '@instapulse_last_following_v1',
  WHITELIST: '@instapulse_whitelist_v1',
  
  ACTION_LOG: '@instapulse_action_log_v1',
};

// ============================================================================
// INJECTED BRIDGE: Sequential pagination with retry & truncation guard
// ============================================================================
const INJECTED_IG_BRIDGE = `
(function() {
  if (window.__INSTAPULSE_BRIDGE_ACTIVE) return;
  window.__INSTAPULSE_BRIDGE_ACTIVE = true;

  const IG_APP_ID = '936619743392459';

  function getCookie(name) {
    const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
    return match ? decodeURIComponent(match[2]) : null;
  }

  const sleep = (ms) => new Promise(r => setTimeout(r, ms));

  function checkAuthStatus() {
    const dsUserId = getCookie('ds_user_id');
    const csrfToken = getCookie('csrftoken');
    window.ReactNativeWebView.postMessage(JSON.stringify({
      type: 'AUTH_STATUS',
      isLoggedIn: Boolean(dsUserId && csrfToken),
      dsUserId: dsUserId || null
    }));
  }

  checkAuthStatus();
  setInterval(checkAuthStatus, 2000);

  async function fetchGraphEdge(userId, edgeType) {
    let allUsers = [];
    let nextMaxId = '';
    let hasNext = true;
    let consecutiveFailures = 0;

    while (hasNext) {
      const url = 'https://www.instagram.com/api/v1/friendships/' + userId + '/' + edgeType + '/?count=50' + (nextMaxId ? '&max_id=' + encodeURIComponent(nextMaxId) : '');
      
      let res = null;
      let data = null;
      let success = false;

      // 3-attempt retry with exponential backoff
      const backoffs = [1500, 3000, 5000];
      for (let attempt = 0; attempt < 3; attempt++) {
        try {
          res = await fetch(url, {
            method: 'GET',
            credentials: 'include',
            headers: {
              'x-ig-app-id': IG_APP_ID,
              'x-csrftoken': getCookie('csrftoken') || '',
              'x-requested-with': 'XMLHttpRequest'
            }
          });

          if (res.ok) {
            data = await res.json();
            success = true;
            consecutiveFailures = 0;
            break;
          } else {
            // Non-200 response — retry after backoff
            if (attempt < 2) await sleep(backoffs[attempt]);
          }
        } catch (err) {
          // Network error — retry after backoff
          if (attempt < 2) await sleep(backoffs[attempt]);
        }
      }

      if (!success) {
        consecutiveFailures++;
        // If we've already fetched some users, throw to prevent returning a truncated list
        throw new Error('Instagram HTTP error while fetching ' + edgeType + ' (page failed after 3 retries, fetched ' + allUsers.length + ' so far)');
      }

      const batch = (data.users || []).map(u => ({
        pk: String(u.pk || u.id),
        username: String(u.username || '').toLowerCase().trim(),
        fullName: String(u.full_name || ''),
        profilePicUrl: String(u.profile_pic_url || ''),
        isVerified: Boolean(u.is_verified),
        isPrivate: Boolean(u.is_private)
      }));

      allUsers = allUsers.concat(batch);

      window.ReactNativeWebView.postMessage(JSON.stringify({
        type: 'SYNC_PROGRESS',
        edgeType: edgeType,
        count: allUsers.length
      }));

      if (data.next_max_id && data.big_list !== false) {
        nextMaxId = data.next_max_id;
        // 600ms - 900ms delay between pages for stability
        await sleep(600 + Math.random() * 300);
      } else {
        hasNext = false;
      }
    }

    // Deduplicate by pk before returning
    const seen = new Set();
    const deduped = [];
    for (let i = 0; i < allUsers.length; i++) {
      const key = allUsers[i].pk;
      if (!seen.has(key)) {
        seen.add(key);
        deduped.push(allUsers[i]);
      }
    }
    return deduped;
  }

  window.runRealInstagramSync = async function() {
    try {
      const myId = getCookie('ds_user_id');
      if (!myId) {
        window.ReactNativeWebView.postMessage(JSON.stringify({ type: 'AUTH_REQUIRED' }));
        return;
      }

      // SEQUENTIAL: fetch followers first, then following
      // This prevents Instagram from throttling parallel requests
      const followers = await fetchGraphEdge(myId, 'followers');
      const following = await fetchGraphEdge(myId, 'following');

      window.ReactNativeWebView.postMessage(JSON.stringify({
        type: 'SYNC_SUCCESS',
        followers: followers,
        following: following
      }));
    } catch (err) {
      window.ReactNativeWebView.postMessage(JSON.stringify({
        type: 'SYNC_ERROR',
        message: err.message || 'Failed to sync graph'
      }));
    }
  };
})();
true;
`;

export default function App() {
  const webViewRef = useRef<WebView>(null);
  const [isLoggedIn, setIsLoggedIn] = useState(false);
  const [showLoginModal, setShowLoginModal] = useState(false);

  const [followers, setFollowers] = useState<IGUser[]>([]);
  const [following, setFollowing] = useState<IGUser[]>([]);
  const [whitelistPks, setWhitelistPks] = useState<Set<string>>(new Set());
  
  const [recentActivity, setRecentActivity] = useState<IGUser[]>([]);
  
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncProgress, setSyncProgress] = useState({ followers: 0, following: 0 });
  const [actionTimestamps, setActionTimestamps] = useState<number[]>([]);

  // Queue Executor State
  const [executorVisible, setExecutorVisible] = useState(false);
  const [actionQueue, setActionQueue] = useState<ActionQueueItem[]>([]);
  const [executorMode, setExecutorMode] = useState<'ASSIST' | 'AUTO_QUEUE'>('AUTO_QUEUE');
  const [busyPk, setBusyPk] = useState<string | null>(null);

  // Track whether we're in a batch operation (to suppress error alerts)
  const isBatchRef = useRef(false);

  // Load from AsyncStorage
  useEffect(() => {
    (async () => {
      try {
        const [recentRaw, folRaw, fingRaw, logRaw, whitelistRaw] = await Promise.all([
          AsyncStorage.getItem(STORAGE_KEYS.RECENT_ACTIVITY),
          AsyncStorage.getItem(STORAGE_KEYS.LAST_FOLLOWERS),
          AsyncStorage.getItem(STORAGE_KEYS.LAST_FOLLOWING),
          AsyncStorage.getItem(STORAGE_KEYS.ACTION_LOG),
          AsyncStorage.getItem(STORAGE_KEYS.WHITELIST),
          
        ]);
        if (recentRaw) setRecentActivity(JSON.parse(recentRaw));
        if (folRaw) setFollowers(JSON.parse(folRaw));
        if (fingRaw) setFollowing(JSON.parse(fingRaw));
        if (logRaw) setActionTimestamps(JSON.parse(logRaw));
        if (whitelistRaw) setWhitelistPks(new Set(JSON.parse(whitelistRaw)));
        
      } catch (e) {
        console.warn('Cache load error:', e);
      }
    })();
  }, []);

  const handleWebViewMessage = async (event: WebViewMessageEvent) => {
    try {
      const msg = JSON.parse(event.nativeEvent.data);

      if (msg.type === 'AUTH_STATUS') {
        setIsLoggedIn(msg.isLoggedIn);
        if (msg.isLoggedIn && showLoginModal) {
          setShowLoginModal(false);
          startLiveAccountSync();
        }
      } else if (msg.type === 'SYNC_PROGRESS') {
        setSyncProgress((prev) => ({
          ...prev,
          [msg.edgeType]: msg.count,
        }));
      } else if (msg.type === 'SYNC_SUCCESS') {
        setIsSyncing(false);

        // TRUNCATION GUARD: Only overwrite cached lists if the new lists are
        // at least 80% the size of the old ones (prevents truncated sync from
        // destroying a good cached list)
        const newFollowers: IGUser[] = msg.followers;
        const newFollowing: IGUser[] = msg.following;

        setFollowers(prev => {
          if (prev.length > 0 && newFollowers.length < prev.length * 0.8) {
            console.warn('[InstaPulse] Truncation guard: new followers list too small, keeping cached');
            return prev;
          }
          AsyncStorage.setItem(STORAGE_KEYS.LAST_FOLLOWERS, JSON.stringify(newFollowers));
          return newFollowers;
        });

        setFollowing(prev => {
          if (prev.length > 0 && newFollowing.length < prev.length * 0.8) {
            console.warn('[InstaPulse] Truncation guard: new following list too small, keeping cached');
            return prev;
          }
          AsyncStorage.setItem(STORAGE_KEYS.LAST_FOLLOWING, JSON.stringify(newFollowing));
          return newFollowing;
        });

      } else if (msg.type === 'SYNC_ERROR') {
        setIsSyncing(false);
        Alert.alert('Sync Alert', msg.message);
      }
    } catch (e) {
      console.warn('Bridge msg parse error', e);
    }
  };

  
  const handleLogout = () => {
    setFollowers([]);
    setFollowing([]);
    setRecentActivity([]);
    setIsLoggedIn(false);
    AsyncStorage.clear();
    if (webViewRef.current) {
      webViewRef.current.injectJavaScript(`
        document.cookie.split(";").forEach(function(c) { 
          document.cookie = c.replace(/^ +/, "").replace(/=.*/, "=;expires=" + new Date().toUTCString() + ";path=/"); 
        });
        window.location.href = 'https://www.instagram.com/accounts/logout/';
      `);
    }
  };

  const startLiveAccountSync = () => {
    if (!isLoggedIn) {
      setShowLoginModal(true);
      return;
    }
    setSyncProgress({ followers: 0, following: 0 });
    setIsSyncing(true);
    webViewRef.current?.injectJavaScript('window.runRealInstagramSync(); true;');
  };

  const handleStartBatchQueue = useCallback((queue: ActionQueueItem[]) => {
    if (queue.length === 0) return;
    isBatchRef.current = queue.length > 1;
    setActionQueue(queue);
    setExecutorVisible(true);
  }, []);

  const handleToggleWhitelist = useCallback(async (pk: string) => {
    setWhitelistPks((prev) => {
      const next = new Set(prev);
      if (next.has(pk)) next.delete(pk);
      else next.add(pk);
      AsyncStorage.setItem(STORAGE_KEYS.WHITELIST, JSON.stringify(Array.from(next)));
      return next;
    });
  }, []);

  const handleInspectProfile = useCallback((username: string) => {
    isBatchRef.current = false;
    setActionQueue([{ username, action: 'FOLLOW' }]);
    setExecutorMode('ASSIST');
    setExecutorVisible(true);
  }, []);

  const handleActionComplete = useCallback((item: ActionQueueItem, status?: 'SUCCESS' | 'SKIPPED_UNAVAILABLE' | 'ERROR') => {
    if (status === 'ERROR') {
      // During batch processing, suppress Alert popup — just log silently
      // The HUD already shows the error message
      if (!isBatchRef.current) {
        Alert.alert('Action Failed', `Could not ${item.action.toLowerCase()} @${item.username}.`);
      }
      return;
    }

    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    const updatedLogs = [...actionTimestamps, Date.now()];
    setActionTimestamps(updatedLogs);
    AsyncStorage.setItem(STORAGE_KEYS.ACTION_LOG, JSON.stringify(updatedLogs));

    let actionType: 'unfollowed' | 'followed' | 'skipped_unavailable' = item.action === 'UNFOLLOW' ? 'unfollowed' : 'followed';
    if (status === 'SKIPPED_UNAVAILABLE') {
      actionType = 'skipped_unavailable';
    }

    const actionTarget = followers.find(u => u.pk === item.pk || u.username === item.username) 
      || following.find(u => u.pk === item.pk || u.username === item.username) 
      || { pk: item.pk || '', username: item.username, fullName: item.username, profilePicUrl: '', isVerified: false, isPrivate: false };
    
    const recentUser = { ...actionTarget, lastAction: actionType, actionTimestamp: Date.now() };

    setRecentActivity(prev => {
      const next = [recentUser, ...prev.filter(u => u.pk !== item.pk && u.username !== item.username)];
      AsyncStorage.setItem(STORAGE_KEYS.RECENT_ACTIVITY, JSON.stringify(next));
      return next;
    });

    // State transitions matched by BOTH pk and username
    if (item.action === 'UNFOLLOW' || status === 'SKIPPED_UNAVAILABLE') {
      setFollowing(prev => {
        const next = prev.filter(u => u.pk !== item.pk && u.username !== item.username);
        AsyncStorage.setItem(STORAGE_KEYS.LAST_FOLLOWING, JSON.stringify(next));
        return next;
      });
    } else if (item.action === 'FOLLOW') {
      setFollowing(prev => {
        const exists = prev.some(u => u.pk === item.pk || u.username === item.username);
        const next = exists ? prev : [recentUser, ...prev];
        AsyncStorage.setItem(STORAGE_KEYS.LAST_FOLLOWING, JSON.stringify(next));
        return next;
      });
    }
  }, [followers, following, actionTimestamps]);

  return (
    <SafeAreaProvider>
      <View style={{ flex: 1, backgroundColor: '#090A0F' }}>
        <StatusBar barStyle="light-content" backgroundColor="#090A0F" />
        
        {/* HIDDEN BACKGROUND WEBVIEW FOR FETCHING FOLLOWERS DATA */}
        <View style={{ height: 0, width: 0, overflow: 'hidden' }}>
          <WebView
            ref={webViewRef}
            source={{ uri: 'https://www.instagram.com/' }}
            injectedJavaScript={INJECTED_IG_BRIDGE}
            onMessage={handleWebViewMessage}
            sharedCookiesEnabled={true}
            thirdPartyCookiesEnabled={true}
            domStorageEnabled={true}
            javaScriptEnabled={true}
          />
        </View>

        <InstaPulseDashboard
          isLoggedIn={isLoggedIn}
          isSyncing={isSyncing}
          syncProgress={syncProgress}
          followers={followers}
          following={following}
          recentActivity={recentActivity}
          onOpenLoginModal={() => setShowLoginModal(true)}
          onStartLiveSync={startLiveAccountSync}
          onLogout={handleLogout}
          onStartBatchQueue={handleStartBatchQueue}
          onInspectProfile={handleInspectProfile}
          actionTimestamps={actionTimestamps}
          busyPk={busyPk}
          whitelistPks={whitelistPks}
          
          onToggleWhitelist={handleToggleWhitelist}
        />

        <InstaActionExecutor
          visible={executorVisible}
          queue={actionQueue}
          mode={executorMode}
          onClose={() => {
            setExecutorVisible(false);
            setExecutorMode('AUTO_QUEUE');
            setBusyPk(null);
            isBatchRef.current = false;
          }}
          onActionStart={(pk) => {
            setBusyPk(pk);
          }}
          onActionComplete={handleActionComplete}
          onQueueFinished={() => {
            setExecutorVisible(false);
            setBusyPk(null);
            isBatchRef.current = false;
          }}
        />

        {/* LOGIN MODAL */}
        <Modal visible={showLoginModal} animationType="slide">
          <SafeAreaView style={{ flex: 1, backgroundColor: '#090A0F' }} edges={['top', 'bottom', 'left', 'right']}>
            <View style={{ flexDirection: 'row', justifyContent: 'space-between', padding: 16 }}>
              <Text style={{ color: '#FFF', fontSize: 16, fontWeight: 'bold' }}>Login to Instagram</Text>
              <TouchableOpacity onPress={() => setShowLoginModal(false)}>
                <Text style={{ color: '#FF3B5C' }}>Close</Text>
              </TouchableOpacity>
            </View>
            <WebView
              source={{ uri: 'https://www.instagram.com/accounts/login/' }}
              sharedCookiesEnabled={true}
              thirdPartyCookiesEnabled={true}
            />
          </SafeAreaView>
        </Modal>
      </View>
    </SafeAreaProvider>
  );
}
