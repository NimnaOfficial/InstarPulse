export interface IGUser {
  lastAction?: 'unfollowed' | 'followed' | 'skipped_unavailable';
  actionTimestamp?: number;
  pk: string;
  username: string;
  fullName: string;
  profilePicUrl: string;
  isVerified: boolean;
  isPrivate: boolean;
}

