import React, { useState } from 'react';
import { Home, FileText, User as UserIcon, Settings, Plus, ChevronRight, Award, Users, BookOpen } from 'lucide-react';
import { User } from '../App';
import { Button } from './ui/button';
import { Card } from './ui/card';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from './ui/dialog';
import { Input } from './ui/input';
import { Label } from './ui/label';
import { Textarea } from './ui/textarea';

interface TeacherDashboardProps {
  user: User;
  onLogout: () => void;
}

type Tab = 'home' | 'records' | 'profile' | 'settings';

interface DefenseTopic {
  id: string;
  title: string;
  description: string;
  date: string;
}

interface StudentRecord {
  id: string;
  studentName: string;
  studentId: string;
  topic: string;
  score: number;
  aiScore: number;
  teacherScore: number;
  date: string;
  feedback: string;
}

export function TeacherDashboard({ user, onLogout }: TeacherDashboardProps) {
  const [activeTab, setActiveTab] = useState<Tab>('home');
  const [showAddTopic, setShowAddTopic] = useState(false);
  const [selectedRecord, setSelectedRecord] = useState<StudentRecord | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [editedUser, setEditedUser] = useState(user);

  // Mock data
  const [topics, setTopics] = useState<DefenseTopic[]>([
    {
      id: '1',
      title: '大数据如何分布式处理有哪些方法？',
      description: '请详细论述大数据分布式处理的主要技术和方法',
      date: '2024-04-25'
    },
    {
      id: '2',
      title: '大数据如何处理海量数据',
      description: '探讨大数据处理的技术架构和优化策略',
      date: '2024-04-25'
    }
  ]);

  const studentRecords: StudentRecord[] = [
    {
      id: '1',
      studentName: '张三',
      studentId: '20240001',
      topic: '大数据如何分布式处理有哪些方法？',
      score: 82,
      aiScore: 80,
      teacherScore: 84,
      date: '2024-04-21',
      feedback: 'AI评分：80分。学生对分布式处理概念理解清晰，但在实际应用案例方面略显不足。\n\n教师评分：84分。回答逻辑清晰，建议加强实践经验。'
    },
    {
      id: '2',
      studentName: '张三',
      studentId: '20240001',
      topic: '大数据如何处理海量数据',
      score: 91,
      aiScore: 90,
      teacherScore: 92,
      date: '2024-04-21',
      feedback: 'AI评分：90分。回答全面，技术理解深入。\n\n教师评分：92分。表现优秀，对技术把握准确。'
    },
    {
      id: '3',
      studentName: '张三',
      studentId: '20240001',
      topic: '大数据如何分布式处理有哪些方法？',
      score: 88,
      aiScore: 87,
      teacherScore: 89,
      date: '2024-04-21',
      feedback: 'AI评分：87分。整体表现良好。\n\n教师评分：89分。建议在案例分析中更加详细。'
    }
  ];

  const [newTopic, setNewTopic] = useState({
    title: '',
    description: '',
    date: ''
  });

  const handleAddTopic = () => {
    if (newTopic.title && newTopic.description && newTopic.date) {
      setTopics([
        ...topics,
        {
          id: String(Date.now()),
          ...newTopic
        }
      ]);
      setNewTopic({ title: '', description: '', date: '' });
      setShowAddTopic(false);
    }
  };

  const handleSaveProfile = () => {
    setIsEditing(false);
  };

  const renderHome = () => (
    <div className="space-y-6 pb-24">
      {/* Welcome Section */}
      <div className="bg-gradient-to-r from-green-400 to-green-500 text-white p-6 rounded-2xl shadow-lg">
        <div className="flex items-center gap-4">
          <div className="w-16 h-16 bg-white rounded-full flex items-center justify-center">
            <UserIcon size={32} className="text-green-500" />
          </div>
          <div>
            <h2 className="text-[20px] font-bold">欢迎，{user.name}</h2>
            <p className="text-sm text-green-100">管理答辩题目和查看学生表现</p>
          </div>
        </div>
      </div>

      {/* Statistics */}
      <div className="grid grid-cols-2 gap-4">
        <Card className="p-4 shadow-md">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 bg-blue-100 rounded-full flex items-center justify-center">
              <BookOpen size={24} className="text-[#008CBA]" />
            </div>
            <div>
              <p className="text-[24px] font-bold text-[#333]">{topics.length}</p>
              <p className="text-sm text-[#666]">答辩题目</p>
            </div>
          </div>
        </Card>
        <Card className="p-4 shadow-md">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 bg-green-100 rounded-full flex items-center justify-center">
              <Users size={24} className="text-[#4CAF50]" />
            </div>
            <div>
              <p className="text-[24px] font-bold text-[#333]">{studentRecords.length}</p>
              <p className="text-sm text-[#666]">答辩记录</p>
            </div>
          </div>
        </Card>
      </div>

      {/* Topic Management */}
      <Card className="p-6 shadow-md">
        <div className="flex justify-between items-center mb-4">
          <h3 className="text-[18px] font-bold text-[#333]">答辩题目设置</h3>
          <Button
            onClick={() => setShowAddTopic(true)}
            className="bg-[#008CBA] hover:bg-[#007aa3] text-white"
            size="sm"
          >
            <Plus size={16} className="mr-1" />
            添加题目
          </Button>
        </div>
        <div className="space-y-3">
          {topics.map((topic) => (
            <div
              key={topic.id}
              className="p-4 bg-gray-50 rounded-lg hover:bg-gray-100 transition"
            >
              <div className="flex justify-between items-start">
                <div className="flex-1">
                  <p className="text-[16px] font-medium text-[#333] mb-1">{topic.title}</p>
                  <p className="text-sm text-[#666] mb-2">{topic.description}</p>
                  <p className="text-xs text-[#888]">答辩时间：{topic.date}</p>
                </div>
                <ChevronRight size={20} className="text-gray-400" />
              </div>
            </div>
          ))}
        </div>
      </Card>

      {/* Recent Student Records */}
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
          {studentRecords.slice(0, 3).map((record) => (
            <div
              key={record.id}
              onClick={() => setSelectedRecord(record)}
              className="flex items-center gap-4 p-4 bg-gray-50 rounded-lg cursor-pointer hover:bg-gray-100 transition"
            >
              <div className="w-12 h-12 bg-blue-100 rounded-full flex items-center justify-center">
                <UserIcon size={24} className="text-[#008CBA]" />
              </div>
              <div className="flex-1">
                <p className="text-[16px] font-medium text-[#333]">{record.studentName}</p>
                <p className="text-sm text-[#666] truncate">{record.topic}</p>
                <p className="text-[12px] text-[#888]">{record.date}</p>
              </div>
              <div className="text-right">
                <p className="text-[20px] font-bold text-[#4CAF50]">{record.score}分</p>
                <button className="text-[#007BFF] text-sm flex items-center gap-1">
                  查看反馈 <ChevronRight size={14} />
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
      <h2 className="text-[24px] font-bold text-[#333] mb-6">学生答辩记录</h2>
      {studentRecords.map((record) => (
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
              <div className="flex items-center gap-2 mb-1">
                <p className="text-[16px] font-medium text-[#333]">{record.studentName}</p>
                <span className="text-xs text-[#888]">({record.studentId})</span>
              </div>
              <p className="text-sm text-[#666] mb-1 truncate">{record.topic}</p>
              <p className="text-[12px] text-[#888]">{record.date}</p>
            </div>
            <div className="text-right">
              <p className="text-[24px] font-bold text-[#4CAF50]">{record.score}分</p>
              <div className="text-xs text-[#888] mt-1">
                <p>AI: {record.aiScore}分</p>
                <p>教师: {record.teacherScore}分</p>
              </div>
              <button className="text-[#007BFF] text-sm mt-1 flex items-center gap-1 ml-auto">
                查看反馈 <ChevronRight size={14} />
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
        <div className="w-20 h-20 bg-gradient-to-br from-green-400 to-green-500 rounded-full flex items-center justify-center mb-4">
          <UserIcon size={40} className="text-white" />
        </div>
        {!isEditing && (
          <Button variant="ghost" className="text-[#007BFF] text-sm">
            修改头像头像
          </Button>
        )}
      </div>

      <Card className="p-6 shadow-md">
        <div className="space-y-4">
          <div>
            <Label className="text-[14px] text-[#666]">换名</Label>
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
            <Label className="text-[14px] text-[#666]">教师编号</Label>
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
            修改修改双系
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
              activeTab === 'home' ? 'text-[#4CAF50]' : 'text-[#888]'
            }`}
          >
            <Home size={24} />
            <span className="text-xs">答辩题目</span>
          </button>
          <button
            onClick={() => setActiveTab('records')}
            className={`flex flex-col items-center gap-1 px-4 py-2 ${
              activeTab === 'records' ? 'text-[#4CAF50]' : 'text-[#888]'
            }`}
          >
            <FileText size={24} />
            <span className="text-xs">答辩记录</span>
          </button>
          <button
            onClick={() => setActiveTab('profile')}
            className={`flex flex-col items-center gap-1 px-4 py-2 ${
              activeTab === 'profile' ? 'text-[#4CAF50]' : 'text-[#888]'
            }`}
          >
            <UserIcon size={24} />
            <span className="text-xs">我的</span>
          </button>
        </div>
      </div>

      {/* Add Topic Dialog */}
      <Dialog open={showAddTopic} onOpenChange={setShowAddTopic}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>添加新的答辩题目</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <div>
              <Label>题目标题</Label>
              <Input
                value={newTopic.title}
                onChange={(e) => setNewTopic({ ...newTopic, title: e.target.value })}
                placeholder="请输入答辩题目"
                className="mt-1"
              />
            </div>
            <div>
              <Label>题目描述</Label>
              <Textarea
                value={newTopic.description}
                onChange={(e) => setNewTopic({ ...newTopic, description: e.target.value })}
                placeholder="请输入题目描述和要求"
                className="mt-1"
                rows={4}
              />
            </div>
            <div>
              <Label>答辩时间</Label>
              <Input
                type="date"
                value={newTopic.date}
                onChange={(e) => setNewTopic({ ...newTopic, date: e.target.value })}
                className="mt-1"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setShowAddTopic(false)}>
              取消
            </Button>
            <Button
              onClick={handleAddTopic}
              className="bg-[#008CBA] hover:bg-[#007aa3] text-white"
            >
              添加题目
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Student Record Detail Dialog */}
      <Dialog open={!!selectedRecord} onOpenChange={() => setSelectedRecord(null)}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>答辩详情</DialogTitle>
          </DialogHeader>
          {selectedRecord && (
            <div className="space-y-4">
              <div>
                <Label className="text-[14px] text-[#666]">学生信息</Label>
                <p className="text-[16px] text-[#333] mt-1">
                  {selectedRecord.studentName} ({selectedRecord.studentId})
                </p>
              </div>
              <div>
                <Label className="text-[14px] text-[#666]">答辩题目</Label>
                <p className="text-[16px] text-[#333] mt-1">{selectedRecord.topic}</p>
              </div>
              <div>
                <Label className="text-[14px] text-[#666]">答辩时间</Label>
                <p className="text-[16px] text-[#333] mt-1">{selectedRecord.date}</p>
              </div>
              <div className="grid grid-cols-3 gap-4">
                <div className="text-center p-3 bg-blue-50 rounded-lg">
                  <p className="text-xs text-[#666] mb-1">综合得分</p>
                  <p className="text-[20px] font-bold text-[#008CBA]">{selectedRecord.score}</p>
                </div>
                <div className="text-center p-3 bg-purple-50 rounded-lg">
                  <p className="text-xs text-[#666] mb-1">AI评分</p>
                  <p className="text-[20px] font-bold text-purple-600">{selectedRecord.aiScore}</p>
                </div>
                <div className="text-center p-3 bg-green-50 rounded-lg">
                  <p className="text-xs text-[#666] mb-1">教师评分</p>
                  <p className="text-[20px] font-bold text-[#4CAF50]">{selectedRecord.teacherScore}</p>
                </div>
              </div>
              <div>
                <Label className="text-[14px] text-[#666]">评分反馈</Label>
                <div className="text-[14px] text-[#666] mt-1 bg-gray-50 p-4 rounded-lg whitespace-pre-line">
                  {selectedRecord.feedback}
                </div>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
