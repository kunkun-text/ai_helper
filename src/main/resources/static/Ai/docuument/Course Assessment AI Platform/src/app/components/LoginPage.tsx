import React from 'react';
import { User, GraduationCap, Bot } from 'lucide-react';
import { Button } from './ui/button';

interface LoginPageProps {
  onLogin: (role: 'student' | 'teacher') => void;
}

export function LoginPage({ onLogin }: LoginPageProps) {
  return (
    <div className="min-h-screen bg-[#f4f6f9] flex items-center justify-center p-4">
      <div className="max-w-md w-full bg-white rounded-2xl shadow-lg p-8">
        <div className="text-center mb-8">
          <h1 className="text-[28px] font-bold text-[#333] mb-2">欢迎登录</h1>
          <p className="text-[16px] text-[#666]">课程考核AI答辩辅助平台</p>
        </div>

        {/* Robot Illustration */}
        <div className="flex justify-center mb-8">
          <div className="relative">
            <div className="w-32 h-32 bg-gradient-to-br from-blue-100 to-blue-200 rounded-full flex items-center justify-center">
              <Bot size={64} className="text-[#008CBA]" />
            </div>
            <div className="absolute -left-4 top-8 w-8 h-8 bg-blue-200 rounded-full opacity-50 animate-pulse"></div>
            <div className="absolute -right-4 top-12 w-6 h-6 bg-blue-300 rounded-full opacity-50 animate-pulse delay-100"></div>
          </div>
        </div>

        {/* Login Options */}
        <div className="space-y-4">
          <button
            onClick={() => onLogin('student')}
            className="w-full bg-white border-2 border-gray-200 rounded-xl p-4 flex items-center gap-4 hover:border-[#008CBA] hover:shadow-md transition-all"
          >
            <div className="w-12 h-12 bg-blue-100 rounded-full flex items-center justify-center">
              <User size={24} className="text-[#008CBA]" />
            </div>
            <div className="flex-1 text-left">
              <p className="text-[16px] font-medium text-[#333]">我是学生</p>
            </div>
            <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>

          <button
            onClick={() => onLogin('teacher')}
            className="w-full bg-white border-2 border-gray-200 rounded-xl p-4 flex items-center gap-4 hover:border-[#4CAF50] hover:shadow-md transition-all"
          >
            <div className="w-12 h-12 bg-green-100 rounded-full flex items-center justify-center">
              <GraduationCap size={24} className="text-[#4CAF50]" />
            </div>
            <div className="flex-1 text-left">
              <p className="text-[16px] font-medium text-[#333]">我是老师</p>
            </div>
            <svg className="w-5 h-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
            </svg>
          </button>
        </div>

        <div className="mt-8 space-y-4">
          <Button
            onClick={() => onLogin('student')}
            className="w-full bg-[#6B9AC4] hover:bg-[#5A89B3] text-white rounded-lg py-3"
          >
            登录
          </Button>
          <p className="text-center text-sm text-[#888]">
            该系统不用于收集个人隐私信息或敏感数据
          </p>
        </div>
      </div>
    </div>
  );
}
