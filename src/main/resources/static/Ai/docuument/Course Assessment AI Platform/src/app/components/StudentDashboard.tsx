import React, { useState } from 'react';
import { Home, FileText, User as UserIcon, Settings, Upload, Clock, Award, ChevronRight } from 'lucide-react';
import { User } from '../App';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from './ui/dialog';
import { Input } from './ui/input';
import { Label } from './ui/label';

interface StudentDashboardProps {
  user: User;
  onLogout: () => void;
}

type Tab = 'home' | 'records' | 'profile' | 'settings';

interface DefenseRecord {
  id: string;
  topic: string;
  score: number;
  date: string;
  feedback: string;
}

export function StudentDashboard({ user, onLogout }: StudentDashboardProps) {
  const [activeTab, setActiveTab] = useState<Tab>('home');
  const [selectedRecord, setSelectedRecord] = useState<DefenseRecord | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [editedUser, setEditedUser] = useState(user);

  // Mock defense records
  const defenseRecords: DefenseRecord[] = [
    {
      id: '1',
      topic: '大数据如何分布式处理有哪些方法？',
      score: 82,
      date: '2024-04-21',
      feedback: '回答清晰，逻辑性强。建议加强对MapReduce原理的理解。'
    },
    {
      id: '2',
      topic: '生物技术对头发是否有提香功能？',
      score: 91,
      date: '2024-04-21',
      feedback: '表现优秀，论述全面。对生物技术应用理解深入。'
    },
    {
      id: '3',
      topic: '大数据如何分布式处理有哪些方法？',
      score: 88,
      date: '2024-04-21',
      feedback: '整体不错，建议在案例分析中更加详细。'
    }
  ];

  const nextDefense = {
    topic: '大数据如何处理海量数据',
    date: '2024-04-25'
  };

  const handleSaveProfile = () => {
    setIsEditing(false);
    // In a real app, this would save to a backend
  };

  const renderHome = () => (
    <div className="space-y-6 pb-24">
      {/* Welcome Section */}
      <div className="bg-gradient-to-r from-blue-400 to-blue-500 text-white p-6 rounded-2xl shadow-lg">
        <div className="flex items-center gap-4">
          <div className="w-16 h-16 bg-white rounded-full flex items-center justify-center">
            <UserIcon size={32} className="text-blue-500" />
          </div>
          <div>
            <h2 className="text-[20px] font-bold">欢迎，{user.name}同学</h2>
            <p className="text-sm text-blue-100">备考评审你的答辩视频帮助学习</p>
          </div>
        </div>
      </div>

      {/* Next Defense */}
      <Card className="p-6 shadow-md">
        <h3 className="text-[18px] font-bold text-[#333] mb-4">下一轮答辩</h3>
        <div className="space-y-3">
          <div>
            <p className="text-sm text-[#666] mb-1">答辩题目</p>
            <p className="text-[16px] text-[#333] font-medium">{nextDefense.topic}</p>
          </div>
          <div className="flex items-center gap-2 text-[#666]">
            <Clock size={16} />
            <span className="text-[14px]">答辩时间：{nextDefense.date}</span>
          </div>
          <Button className="w-full bg-[#4CAF50] hover:bg-[#45a049] text-white rounded-lg mt-4 py-6">
            开始答辩
          </Button>
        </div>
      </Card>

      {/* Upload Video Section */}
      <Card className="p-6 shadow-md">
        <div className="flex flex-col items-center justify-center py-8 border-2 border-dashed border-gray-300 rounded-lg">
          <div className="w-16 h-16 bg-blue-100 rounded-full flex items-center justify-center mb-4">
            <Upload size={32} className="text-[#008CBA]" />
          </div>
          <p className="text-[16px] text-[#666] mb-4">上传答辩视频</p>
          <Button variant="outline" className="border-[#008CBA] text-[#008CBA] hover:bg-blue-50">
            选择视频，开始上传
          </Button>
        </div>
      </Card>

      {/* Recent Records Preview */}
      <Card className="p-6 shadow-md">
        <div className="flex justify-between items-center mb-4">
          <h3 className="text-[18px] font-bold text-[#333]">学生答辩记录</h3>
          <button
            onClick={() => setActiveTab('records')}
            className="text-[#007BFF] text-sm flex items-center gap-1"
          >
            查看全部 <ChevronRight size={16} />
          </button>
        </div>
        <div className="space-y-3">
          {defenseRecords.slice(0, 2).map((record) => (
            <div
              key={record.id}
              onClick={() => setSelectedRecord(record)}
              className="flex items-center gap-4 p-4 bg-gray-50 rounded-lg cursor-pointer hover:bg-gray-100 transition"
            >
              <div className="w-12 h-12 bg-blue-100 rounded-full flex items-center justify-center">
                <UserIcon size={24} className="text-[#008CBA]" />
              </div>
              <div className="flex-1">
                <p className="text-[16px] font-medium text-[#333]">{user.name}</p>
                <p className="text-sm text-[#666] truncate">{record.topic}</p>
                <p className="text-[12px] text-[#888]">{record.date}</p>
              </div>
              <div className="text-right">
                <p className="text-[20px] font-bold text-[#4CAF50]">{record.score}分</p>
                <button className="text-[#007BFF] text-sm flex items-center gap-1">
                  查看详情 <ChevronRight size={14} />
                </button>
              </div>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );

  const renderRecords = () => (
    <div className="space-y-4 pb-24">
      <h2 className="text-[24px] font-bold text-[#333] mb-6">答辩记录</h2>
      {defenseRecords.map((record) => (
        <Card
          key={record.id}
          className="p-6 shadow-md cursor-pointer hover:shadow-lg transition"
          onClick={() => setSelectedRecord(record)}
        >
          <div className="flex items-center gap-4">
            <div className="w-12 h-12 bg-blue-100 rounded-full flex items-center justify-center">
              <UserIcon size={24} className="text-[#008CBA]" />
            </div>
            <div className="flex-1">
              <p className="text-[16px] font-medium text-[#333]">{user.name}</p>
              <p className="text-sm text-[#666] mt-1 truncate">{record.topic}</p>
              <p className="text-[12px] text-[#888] mt-1">{record.date}</p>
            </div>
            <div className="text-right">
              <p className="text-[24px] font-bold text-[#4CAF50]">{record.score}分</p>
              <button className="text-[#007BFF] text-sm mt-1 flex items-center gap-1">
                查看详情 <ChevronRight size={14} />
              </button>
            </div>
          </div>
        </Card>
      ))}
    </div>
  );

  const renderProfile = () => (
    <div className="space-y-6 pb-24">
      <h2 className="text-[24px] font-bold text-[#333] mb-6">个人信息</h2>
      
      {/* Avatar Section */}
      <div className="flex flex-col items-center mb-6">
        <div className="w-20 h-20 bg-gradient-to-br from-blue-400 to-blue-500 rounded-full flex items-center justify-center mb-4">
          <UserIcon size={40} className="text-white" />
        </div>
        {!isEditing && (
          <button className="text-[#007BFF] text-sm">点击修改头像</button>
        )}
      </div>

      <Card className="p-6 shadow-md">
        <div className="space-y-4">
          <div>
            <Label className="text-[14px] text-[#666]">用户名/学号</Label>
            {isEditing ? (
              <Input
                value={editedUser.name}
                onChange={(e) => setEditedUser({ ...editedUser, name: e.target.value })}
                className="mt-1"
              />
            ) : (
              <p className="text-[16px] text-[#333] mt-1">{user.name}</p>
            )}
          </div>

          <div>
            <Label className="text-[14px] text-[#666]">学号</Label>
            <p className="text-[16px] text-[#333] mt-1">{user.id}</p>
          </div>

          <div>
            <Label className="text-[14px] text-[#666]">手机号码</Label>
            {isEditing ? (
              <Input
                value={editedUser.phone}
                onChange={(e) => setEditedUser({ ...editedUser, phone: e.target.value })}
                className="mt-1"
              />
            ) : (
              <p className="text-[16px] text-[#333] mt-1">{user.phone}</p>
            )}
          </div>

          <div>
            <Label className="text-[14px] text-[#666]">邮箱</Label>
            {isEditing ? (
              <Input
                value={editedUser.email}
                onChange={(e) => setEditedUser({ ...editedUser, email: e.target.value })}
                className="mt-1"
              />
            ) : (
              <p className="text-[16px] text-[#333] mt-1">{user.email}</p>
            )}
          </div>
        </div>

        {isEditing ? (
          <Button
            onClick={handleSaveProfile}
            className="w-full bg-[#008CBA] hover:bg-[#007aa3] text-white rounded-lg mt-6 py-6"
          >
            保存信息
          </Button>
        ) : (
          <Button
            onClick={() => setIsEditing(true)}
            className="w-full bg-[#008CBA] hover:bg-[#007aa3] text-white rounded-lg mt-6 py-6"
          >
            修改个人信息
          </Button>
        )}
      </Card>

      <Card className="p-6 shadow-md">
        <button
          onClick={onLogout}
          className="w-full text-left flex items-center gap-3 text-[#666] hover:text-[#333] transition"
        >
          <div className="w-10 h-10 bg-gray-100 rounded-full flex items-center justify-center">
            <Settings size={20} />
          </div>
          <span className="text-[16px]">退出登录</span>
        </button>
      </Card>
    </div>
  );

  const renderSettings = () => (
    <div className="space-y-6 pb-24">
      <h2 className="text-[24px] font-bold text-[#333] mb-6">设置</h2>
      <Card className="p-6 shadow-md">
        <div className="space-y-4">
          <button className="w-full text-left flex items-center justify-between p-4 hover:bg-gray-50 rounded-lg transition">
            <span className="text-[16px] text-[#333]">通知设置</span>
            <ChevronRight size={20} className="text-gray-400" />
          </button>
          <button className="w-full text-left flex items-center justify-between p-4 hover:bg-gray-50 rounded-lg transition">
            <span className="text-[16px] text-[#333]">隐私设置</span>
            <ChevronRight size={20} className="text-gray-400" />
          </button>
          <button className="w-full text-left flex items-center justify-between p-4 hover:bg-gray-50 rounded-lg transition">
            <span className="text-[16px] text-[#333]">关于我们</span>
            <ChevronRight size={20} className="text-gray-400" />
          </button>
          <button
            onClick={onLogout}
            className="w-full text-left flex items-center justify-between p-4 hover:bg-red-50 rounded-lg transition text-[#F44336]"
          >
            <span className="text-[16px]">退出登录</span>
            <ChevronRight size={20} />
          </button>
        </div>
      </Card>
    </div>
  );

  return (
    <div className="min-h-screen bg-[#f4f6f9]">
      {/* Header */}
      <div className="bg-white border-b border-gray-200 px-6 py-4 sticky top-0 z-10">
        <h1 className="text-center text-[18px] font-medium text-[#333]">
          课程考核AI答辩辅助
        </h1>
      </div>

      {/* Main Content */}
      <div className="max-w-2xl mx-auto p-6">
        {activeTab === 'home' && renderHome()}
        {activeTab === 'records' && renderRecords()}
        {activeTab === 'profile' && renderProfile()}
        {activeTab === 'settings' && renderSettings()}
      </div>

      {/* Bottom Navigation */}
      <div className="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-200 shadow-lg">
        <div className="max-w-2xl mx-auto flex justify-around items-center py-3">
          <button
            onClick={() => setActiveTab('home')}
            className={`flex flex-col items-center gap-1 px-4 py-2 ${
              activeTab === 'home' ? 'text-[#008CBA]' : 'text-[#888]'
            }`}
          >
            <Home size={24} />
            <span className="text-xs">答辩题目</span>
          </button>
          <button
            onClick={() => setActiveTab('records')}
            className={`flex flex-col items-center gap-1 px-4 py-2 ${
              activeTab === 'records' ? 'text-[#008CBA]' : 'text-[#888]'
            }`}
          >
            <FileText size={24} />
            <span className="text-xs">答辩记录</span>
          </button>
          <button
            onClick={() => setActiveTab('profile')}
            className={`flex flex-col items-center gap-1 px-4 py-2 ${
              activeTab === 'profile' ? 'text-[#008CBA]' : 'text-[#888]'
            }`}
          >
            <UserIcon size={24} />
            <span className="text-xs">我的</span>
          </button>
        </div>
      </div>

      {/* Defense Record Detail Dialog */}
      <Dialog open={!!selectedRecord} onOpenChange={() => setSelectedRecord(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>答辩详情</DialogTitle>
          </DialogHeader>
          {selectedRecord && (
            <div className="space-y-4">
              <div>
                <Label className="text-[14px] text-[#666]">答辩题目</Label>
                <p className="text-[16px] text-[#333] mt-1">{selectedRecord.topic}</p>
              </div>
              <div>
                <Label className="text-[14px] text-[#666]">答辩时间</Label>
                <p className="text-[16px] text-[#333] mt-1">{selectedRecord.date}</p>
              </div>
              <div>
                <Label className="text-[14px] text-[#666]">得分</Label>
                <div className="flex items-center gap-2 mt-1">
                  <Award className="text-[#4CAF50]" size={24} />
                  <p className="text-[24px] font-bold text-[#4CAF50]">{selectedRecord.score}分</p>
                </div>
              </div>
              <div>
                <Label className="text-[14px] text-[#666]">AI反馈</Label>
                <p className="text-[14px] text-[#666] mt-1 bg-gray-50 p-4 rounded-lg">
                  {selectedRecord.feedback}
                </p>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
