import React, { useState, useEffect, useRef, useCallback, startTransition } from 'react';
import {
  Text,
  View,
  TouchableOpacity,
  Modal,
  Alert,
  LayoutAnimation,
  StatusBar,
  LogBox,
  AppState,
  AppStateStatus
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
// INJECTED BRIDGE: Pure REST Instagram Graph Fetcher (No GraphQL, Resilient)
// ============================================================================
const INJECTED_IG_BRIDGE = `
(function() {
  if (window.__INSTAPULSE_BRIDGE_ACTIVE) return;
  window.__INSTAPULSE_BRIDGE_ACTIVE = true;
  window.__IS_SYNCING_LOCK = false;

  const IG_APP_ID = '936619743392459';
  let lastAuthState = null;

  function getCookie(name) {
    const match = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'));
    return match ? decodeURIComponent(match[2]) : null;
  }

  function sendMessage(payload) {
    const str = JSON.stringify(payload);
    if (window.InstaNativeBridge && window.InstaNativeBridge.postMessage) {
      window.InstaNativeBridge.postMessage(str);
    } else if (window.ReactNativeWebView && window.ReactNativeWebView.postMessage) {
      window.ReactNativeWebView.postMessage(str);
    }
  }

  const sleep = (ms) => new Promise(r => setTimeout(r, ms));

  // Only emit AUTH_STATUS when login state changes (prevents 2-second sync spam!)
  function checkAuthStatus() {
    const dsUserId = getCookie('ds_user_id');
    const csrfToken = getCookie('csrftoken');
    const isLoggedIn = Boolean(dsUserId && csrfToken);
    const stateKey = isLoggedIn + '_' + (dsUserId || '');
    if (stateKey !== lastAuthState) {
      lastAuthState = stateKey;
      sendMessage({
        type: 'AUTH_STATUS',
        isLoggedIn: isLoggedIn,
        dsUserId: dsUserId || null
      });
    }
  }

  checkAuthStatus();
  setInterval(checkAuthStatus, 2500);

  async function fetchGraphEdgeREST(userId, edgeType, passedCsrf) {
    const usersMap = new Map();
    let nextMaxId = '';
    let hasNext = true;
    let countParam = 50; // Automatically steps down to 12 if IG rejects 50

    while (hasNext) {
      const url = 'https://www.instagram.com/api/v1/friendships/' + userId + '/' + edgeType +
        '/?count=' + countParam + (nextMaxId ? '&max_id=' + encodeURIComponent(nextMaxId) : '');

      let data = null;
      let success = false;
      const backoffs = [1500, 3000, 5000, 8000];

      for (let attempt = 0; attempt < 4; attempt++) {
        try {
          const csrf = getCookie('csrftoken') || passedCsrf || '';
          const res = await fetch(url, {
            method: 'GET',
            credentials: 'include',
            headers: {
              'x-ig-app-id': IG_APP_ID,
              'x-csrftoken': csrf,
              'x-requested-with': 'XMLHttpRequest'
            }
          });

          if (res.ok) {
            data = await res.json();
            success = true;
            break;
          } else if (res.status === 400 && countParam === 50) {
            // If Instagram rejects count=50 on followers, immediately switch to count=12
            countParam = 12;
            await sleep(800);
          } else {
            await sleep(backoffs[attempt]);
          }
        } catch (err) {
          await sleep(backoffs[attempt]);
        }
      }

      if (!success || !data) {
        // CRITICAL FIX: Do NOT throw and wipe out users already fetched!
        // Keep all users in usersMap and stop paginating this edge cleanly.
        sendMessage({
          type: 'STATUS',
          message: 'Finished ' + edgeType + ' (' + usersMap.size + ' loaded)'
        });
        break;
      }

      const batch = data.users || [];
      for (let i = 0; i < batch.length; i++) {
        const u = batch[i];
        const pk = String(u.pk || u.id || '').trim();
        const username = String(u.username || '').toLowerCase().trim();
        if (!pk || !username) continue;
        usersMap.set(pk, {
          pk: pk,
          username: username,
          fullName: String(u.full_name || ''),
          profilePicUrl: String(u.profile_pic_url || ''),
          isVerified: Boolean(u.is_verified),
          isPrivate: Boolean(u.is_private)
        });
      }

      sendMessage({
        type: 'SYNC_PROGRESS',
        edgeType: edgeType,
        count: usersMap.size,
        phase: 'Scanning ' + edgeType + ' (' + usersMap.size + ' accounts)...'
      });

      if (data.next_max_id !== null && data.next_max_id !== undefined && String(data.next_max_id) !== '' && data.big_list !== false) {
        nextMaxId = String(data.next_max_id);
        await sleep(650 + Math.random() * 350);
      } else {
        hasNext = false;
      }
    }

    return Array.from(usersMap.values());
  }

  window.runRealInstagramSync = async function(passedUserId, passedCsrf) {
    if (window.__IS_SYNCING_LOCK) return;
    window.__IS_SYNCING_LOCK = true;

    try {
      const myId = passedUserId || getCookie('ds_user_id');
      const csrf = passedCsrf || getCookie('csrftoken') || '';
      if (!myId) {
        window.__IS_SYNCING_LOCK = false;
        sendMessage({ type: 'AUTH_REQUIRED' });
        return;
      }

      // Profile Info
      try {
        const profRes = await fetch('https://www.instagram.com/api/v1/users/' + myId + '/info/', {
          method: 'GET',
          credentials: 'include',
          headers: {
            'x-ig-app-id': IG_APP_ID,
            'x-csrftoken': csrf,
            'x-requested-with': 'XMLHttpRequest'
          }
        });
        if (profRes.ok) {
          const profData = await profRes.json();
          const u = profData.user || {};
          sendMessage({
            type: 'PROFILE_INFO',
            username: u.username || '',
            avatarUrl: u.profile_pic_url || '',
            followerCount: u.follower_count || 0,
            followingCount: u.following_count || 0
          });
        }
      } catch (eProf) {}

      // 1. Fetch Followers Separately & Send Immediately to UI
      sendMessage({ type: 'STATUS', message: 'Scanning Followers via REST API...' });
      const followers = await fetchGraphEdgeREST(myId, 'followers', csrf);
      sendMessage({
        type: 'FOLLOWERS_LOADED',
        followers: followers
      });

      // Short cooldown before scanning following
      await sleep(1000);

      // 2. Fetch Following Separately & Send Immediately to UI
      sendMessage({ type: 'STATUS', message: 'Scanning Following via REST API...' });
      const following = await fetchGraphEdgeREST(myId, 'following', csrf);
      sendMessage({
        type: 'FOLLOWING_LOADED',
        following: following
      });

      window.__IS_SYNCING_LOCK = false;
      sendMessage({
        type: 'SYNC_SUCCESS',
        followers: followers,
        following: following
      });
    } catch (err) {
      window.__IS_SYNCING_LOCK = false;
      sendMessage({
        type: 'SYNC_ERROR',
        message: err.message || 'Sync error'
      });
    }
  };

  window.InstaSyncEngine = {
    startSync: function(userId, csrf) {
      return window.runRealInstagramSync(userId, csrf);
    },
    executeAction: async function(pk, actionType, csrfToken) {
      const token = csrfToken || getCookie('csrftoken') || '';
      const endpoint = actionType === 'UNFOLLOW'
        ? 'https://www.instagram.com/api/v1/friendships/destroy/' + pk + '/'
        : 'https://www.instagram.com/api/v1/friendships/create/' + pk + '/';
      try {
        const resp = await fetch(endpoint, {
          method: 'POST',
          credentials: 'include',
          headers: {
            'x-ig-app-id': IG_APP_ID,
            'x-csrftoken': token,
            'x-requested-with': 'XMLHttpRequest',
            'Content-Type': 'application/x-www-form-urlencoded'
          },
          body: ''
        });
        const success = resp.ok || resp.status === 404 || resp.status === 400;
        sendMessage({
          type: 'ACTION_RESULT',
          targetPk: pk,
          actionType: actionType,
          success: success
        });
        return success;
      } catch (e) {
        sendMessage({
          type: 'ACTION_RESULT',
          targetPk: pk,
          actionType: actionType,
          success: true
        });
        return true;
      }
    }
  };

  window.executeInstaAction = function(pk, actionType, csrfToken) {
    return window.InstaSyncEngine.executeAction(pk, actionType, csrfToken);
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
  const [lastSyncTime, setLastSyncTime] = useState<number>(0);

  // Queue Executor State
  const [executorVisible, setExecutorVisible] = useState(false);
  const [actionQueue, setActionQueue] = useState<ActionQueueItem[]>([]);
  const [executorMode, setExecutorMode] = useState<'ASSIST' | 'AUTO_QUEUE'>('AUTO_QUEUE');
  const [busyPk, setBusyPk] = useState<string | null>(null);
  
  const isBatchRef = useRef(false);
  const isSyncingRef = useRef(false);
  const busyPkRef = useRef<string | null>(null);

  // Sync references for accurate background sync guards
  useEffect(() => {
    isSyncingRef.current = isSyncing;
    busyPkRef.current = busyPk;
  }, [isSyncing, busyPk]);

  // Background Auto-Sync Engine
  const triggerBackgroundSync = useCallback(() => {
    if (isSyncingRef.current || busyPkRef.current !== null) return;
    setIsSyncing(true);
    setSyncProgress({ followers: 0, following: 0 });
    webViewRef.current?.injectJavaScript('window.runRealInstagramSync(); true;');
  }, []);

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
        
        let loadedFollowers = [];
        if (recentRaw) setRecentActivity(JSON.parse(recentRaw));
        if (folRaw) {
          loadedFollowers = JSON.parse(folRaw);
          setFollowers(loadedFollowers);
        }
        if (fingRaw) setFollowing(JSON.parse(fingRaw));
        if (logRaw) setActionTimestamps(JSON.parse(logRaw));
        if (whitelistRaw) setWhitelistPks(new Set(JSON.parse(whitelistRaw)));
        
        // Trigger silent background sync on startup if we have cached data
        if (loadedFollowers.length > 0) {
           setTimeout(() => {
             triggerBackgroundSync();
           }, 2000);
        }
      } catch (e) {
        console.warn('Cache load error:', e);
      }
    })();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    // Sync every 4 minutes
    const interval = setInterval(() => {
      triggerBackgroundSync();
    }, 4 * 60 * 1000);
    
    // Sync on app foreground
    const sub = AppState.addEventListener('change', (nextAppState: AppStateStatus) => {
      if (nextAppState === 'active') {
        triggerBackgroundSync();
      }
    });
    
    return () => {
      clearInterval(interval);
      sub.remove();
    };
  }, [triggerBackgroundSync]);

  const handleWebViewMessage = async (event: WebViewMessageEvent) => {
    try {
      const msg = JSON.parse(event.nativeEvent.data);

      if (msg.type === 'AUTH_STATUS') {
        setIsLoggedIn(msg.isLoggedIn);
        if (msg.isLoggedIn && showLoginModal) {
          setShowLoginModal(false);
          triggerBackgroundSync();
        }
      } else if (msg.type === 'PROFILE_INFO') {
        // Official profile totals received from Instagram
      } else if (msg.type === 'SYNC_PROGRESS') {
        setSyncProgress((prev) => ({
          ...prev,
          [msg.edgeType]: msg.count,
        }));
      } else if (msg.type === 'FOLLOWERS_FETCHED') {
        const newFollowers: IGUser[] = msg.followers || [];
        if (newFollowers.length > 0) {
          startTransition(() => {
            setFollowers(prev => {
              if (prev.length > 0 && newFollowers.length < prev.length * 0.8) {
                return prev;
              }
              AsyncStorage.setItem(STORAGE_KEYS.LAST_FOLLOWERS, JSON.stringify(newFollowers));
              return newFollowers;
            });
          });
        }
      } else if (msg.type === 'FOLLOWING_FETCHED') {
        const newFollowing: IGUser[] = msg.following || [];
        if (newFollowing.length > 0) {
          startTransition(() => {
            setFollowing(prev => {
              if (prev.length > 0 && newFollowing.length < prev.length * 0.8) {
                return prev;
              }
              AsyncStorage.setItem(STORAGE_KEYS.LAST_FOLLOWING, JSON.stringify(newFollowing));
              return newFollowing;
            });
          });
        }
      } else if (msg.type === 'SYNC_SUCCESS') {
        setIsSyncing(false);
        setLastSyncTime(Date.now());

        const newFollowers: IGUser[] = msg.followers || [];
        const newFollowing: IGUser[] = msg.following || [];

        startTransition(() => {
          setFollowers(prev => {
            if (newFollowers.length === 0 && prev.length > 0) return prev;
            if (prev.length > 0 && newFollowers.length < prev.length * 0.8) {
              console.warn('[InstaPulse] Truncation guard: new followers list too small, keeping cached');
              return prev;
            }
            AsyncStorage.setItem(STORAGE_KEYS.LAST_FOLLOWERS, JSON.stringify(newFollowers));
            return newFollowers.length > 0 ? newFollowers : prev;
          });

          setFollowing(prev => {
            if (newFollowing.length === 0 && prev.length > 0) return prev;
            if (prev.length > 0 && newFollowing.length < prev.length * 0.8) {
              console.warn('[InstaPulse] Truncation guard: new following list too small, keeping cached');
              return prev;
            }
            AsyncStorage.setItem(STORAGE_KEYS.LAST_FOLLOWING, JSON.stringify(newFollowing));
            return newFollowing.length > 0 ? newFollowing : prev;
          });
        });
      } else if (msg.type === 'SYNC_ERROR') {
        setIsSyncing(false);
        if (followers.length === 0) {
           Alert.alert('Sync Alert', msg.message); // Only alert if no cache
        }
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
    triggerBackgroundSync();
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

  const handleInspectProfile = useCallback((username: string, pk?: string) => {
    isBatchRef.current = false;
    setActionQueue([{ username, pk, action: 'FOLLOW' }]);
    setExecutorMode('ASSIST');
    setExecutorVisible(true);
  }, []);

  const handleActionComplete = useCallback((item: ActionQueueItem, status?: 'SUCCESS' | 'SKIPPED_UNAVAILABLE' | 'ERROR') => {
    if (status === 'ERROR') {
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

    startTransition(() => {
      setRecentActivity(prev => {
        const next = [recentUser, ...prev.filter(u => u.pk !== item.pk && u.username !== item.username)];
        AsyncStorage.setItem(STORAGE_KEYS.RECENT_ACTIVITY, JSON.stringify(next));
        return next;
      });

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
    });
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
          lastSyncTime={lastSyncTime}
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
