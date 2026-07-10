import { create } from 'zustand';

/** 사이드바 세션 상태 (docs/01 CLIENT). 서버가 진실이고 이건 화면 상태다. */
type SessionState = {
	currentSessionId: string | null;
	select: (id: string | null) => void;
};

export const useSessionStore = create<SessionState>((set) => ({
	currentSessionId: null,
	select: (id) => set({ currentSessionId: id }),
}));
