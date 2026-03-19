import React, { useState } from 'react';
import { LoginPage } from './components/LoginPage';
import { StudentDashboard } from './components/StudentDashboard';
import { TeacherDashboard } from './components/TeacherDashboard';

export type UserRole = 'student' | 'teacher' | null;

export interface User {
  name: string;
  id: string;
  role: UserRole;
  phone?: string;
  email?: string;
  avatar?: string;
}

export default function App() {
  const [user, setUser] = useState<User | null>(null);

  const handleLogin = (role: 'student' | 'teacher') => {
    if (role === 'student') {
      setUser({
        name: '张三',
        id: '20240001',
        role: 'student',
        phone: '13812345678',
        email: 'zhangsan123@example.com'
      });
    } else {
      setUser({
        name: '张老师',
        id: 'T1001',
        role: 'teacher',
        phone: '13812345678',
        email: 'zhanglaoshi456@example.com'
      });
    }
  };

  const handleLogout = () => {
    setUser(null);
  };

  if (!user) {
    return <LoginPage onLogin={handleLogin} />;
  }

  if (user.role === 'student') {
    return <StudentDashboard user={user} onLogout={handleLogout} />;
  }

  return <TeacherDashboard user={user} onLogout={handleLogout} />;
}
