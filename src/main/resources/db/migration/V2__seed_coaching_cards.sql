-- 코칭 카드 초안 16장. 팀이 직접 쓴 문장이며 공공자료 원문을 옮기지 않았다.
-- 근거 태그는 NCAEP 2020의 근거기반 실제 분류를 "인용"만 한다. 검수자(reviewer)는 전문가 검수 후 채운다.

INSERT INTO coaching_cards (id, code, title, body, situation, metric_focus, evidence_tag, source_title, source_url, author, reviewer, active) VALUES
(1, 'FIRST-01', '첫 기록, 정말 잘하셨어요',
 '바쁜 하루 중에 아이와 노는 시간을 따로 내고 기록까지 남기셨어요. 그것만으로도 큰 걸음이에요. 다음 기록은 오늘과 비슷한 시간대, 같은 장소에서 찍어 보세요. 조건이 비슷할수록 우리 집의 변화가 더 잘 보여요.',
 'FIRST_RECORD', 'GENERAL', '근거기반 실제: 부모 실행 중재(Parent-Implemented Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(2, 'FIRST-02', '아이가 고른 놀이로 시작해요',
 '아이가 먼저 손을 뻗는 장난감이나 놀이가 있다면 그게 오늘의 놀이예요. 부모님이 이끌기보다 아이가 하는 것을 옆에서 따라 하고, 아이가 보는 것을 같이 바라봐 주세요. 아이는 자기가 고른 놀이에서 더 오래, 더 즐겁게 머물러요.',
 'FIRST_RECORD', 'GENERAL', '근거기반 실제: 자연적 중재(Naturalistic Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(3, 'JP-G-01', '함께한 시간이 늘었어요',
 '지난 기록보다 아이 곁에 함께 머문 시간이 늘었어요. 잘하고 계세요. 이번 주에는 아이와 얼굴을 마주 볼 수 있는 자리에 앉아 보세요. 바닥에 함께 앉거나 아이 눈높이로 몸을 낮추면 아이가 부모님 얼굴을 보기 쉬워져요.',
 'GROWING', 'JOINT_PLAY', '근거기반 실제: 자연적 중재(Naturalistic Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(4, 'JP-G-02', '주고받기를 한 번 더',
 '함께 노는 시간이 늘어난 지금이 주고받기를 연습하기 좋은 때예요. 공을 굴리고 기다리기, 블록을 하나씩 번갈아 쌓기처럼 차례가 있는 놀이를 해 보세요. 부모님 차례에는 천천히 시범을 보이고, 아이 차례가 오면 기대하는 얼굴로 기다려 주세요.',
 'GROWING', 'JOINT_PLAY', '근거기반 실제: 모델링(Modeling)의 원리 참고',
 'AFIRM Modules, FPG Child Development Institute', 'https://afirm.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(5, 'JP-S-01', '하루 5분이면 충분해요',
 '함께 노는 시간이 늘지 않는 것 같아도 괜찮아요. 아이마다 속도가 다르고 그날의 컨디션도 달라요. 길게 놀려고 애쓰기보다 목욕 전 5분, 잠들기 전 5분처럼 매일 같은 때에 짧게 함께하는 시간을 정해 보세요. 반복되는 짧은 시간이 아이에게는 더 편안해요.',
 'STEADY', 'JOINT_PLAY', '근거기반 실제: 부모 실행 중재(Parent-Implemented Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(6, 'JP-A-01', '아이 눈높이로 내려가 보세요',
 '부모님이 서 있거나 소파에 앉아 있으면 아이는 부모님 얼굴을 보기 어려워요. 오늘은 아이가 노는 바닥에 함께 엎드리거나 앉아 보세요. 아이가 하는 놀이를 그대로 따라 하기만 해도 아이는 부모님과 함께 있다는 걸 느껴요.',
 'ANY', 'JOINT_PLAY', '근거기반 실제: 자연적 중재(Naturalistic Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(7, 'RS-G-01', '아이 소리에 더 빨리 답해 주셨어요',
 '아이가 소리를 낸 뒤 부모님이 답하는 시간이 짧아졌어요. 아이는 "내 소리가 닿는구나"를 배우고 있어요. 이번 주에는 아이 소리나 말을 그대로 따라 한 뒤 한 단어만 더해 보세요. 아이가 "공" 하면 "공 굴려"처럼요.',
 'GROWING', 'RESPONSE', '근거기반 실제: 모델링(Modeling)의 원리 참고',
 'AFIRM Modules, FPG Child Development Institute', 'https://afirm.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(8, 'RS-S-01', '작은 소리에도 바로 반응해 주기',
 '아이가 내는 작은 소리, 손짓, 눈길도 아이가 건네는 말이에요. 오늘은 아이가 소리를 내면 하던 일을 잠깐 멈추고 바로 눈을 맞추며 답해 보세요. 정확한 말이 아니어도 괜찮아요. "그랬구나", "또 해 볼까?" 한마디면 충분해요.',
 'STEADY', 'RESPONSE', '근거기반 실제: 강화(Reinforcement)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(9, 'RS-A-01', '살짝 기다렸다가, 바로 답해 주기',
 '아이가 원하는 것을 바로 건네기 전에 기대하는 얼굴로 3초쯤 기다려 보세요. 아이가 소리나 손짓으로 먼저 표현할 기회가 생겨요. 아이가 표현하면 그때는 기다리지 말고 바로 반응해 주세요. 기다림과 빠른 반응이 짝을 이룰 때 아이의 표현이 늘어나요.',
 'ANY', 'RESPONSE', '근거기반 실제: 시간 지연(Time Delay)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(10, 'CA-G-01', '편안한 시간이 늘었어요',
 '아이가 크게 우는 일이 줄어든 모습이 기록에 보여요. 부모님이 만들어 준 편안한 하루 덕분이에요. 이번 주에는 하루 일과를 그림이나 사진으로 붙여 두고 다음에 할 일을 함께 손가락으로 짚어 보세요. 앞으로 일어날 일을 알면 아이는 더 편안해져요.',
 'GROWING', 'CALM', '근거기반 실제: 시각적 지원(Visual Supports)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(11, 'CA-S-01', '바꾸기 전에 미리 알려주기',
 '놀이를 멈추거나 장소를 옮길 때 아이가 속상해하는 경우가 많아요. 바꾸기 몇 분 전에 "이거 두 번만 더 하고 목욕하자"처럼 미리 알려 주세요. 손가락으로 횟수를 보여 주거나 타이머를 함께 보면 아이가 마음의 준비를 할 수 있어요.',
 'STEADY', 'CALM', '근거기반 실제: 선행사건 기반 중재(Antecedent-Based Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(12, 'CA-A-01', '울음이 그친 뒤, 짧고 따뜻하게',
 '아이가 울 때는 곁에 머물며 낮고 차분한 목소리로 "속상했구나" 하고 마음을 읽어 주세요. 울음이 잦아들면 "진정했네, 잘했어"처럼 짧게 알아봐 주세요. 진정한 순간을 알아봐 주는 경험이 쌓이면 아이가 스스로 마음을 가라앉히는 힘이 자라요.',
 'ANY', 'CALM', '근거기반 실제: 차별 강화(Differential Reinforcement)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(13, 'GEN-A-01', '오늘 좋았던 순간 하나',
 '오늘 아이와 함께한 시간 중 좋았던 순간 하나를 떠올려 보세요. 아이가 웃었던 때, 눈이 마주쳤던 때처럼 아주 작은 순간이어도 괜찮아요. 부모님이 기다려 주고 곁에 있어 준 시간이 그 순간을 만들었어요. 오늘도 수고 많으셨어요.',
 'ANY', 'GENERAL', '근거기반 실제: 부모 실행 중재(Parent-Implemented Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(14, 'GEN-S-01', '잠시 머무는 시기도 성장의 일부예요',
 '기록이 크게 달라지지 않는 시기가 있어요. 아이는 겉으로 보이지 않는 곳에서도 자라고 있어요. 지금 하고 계신 것을 그대로 이어가 주세요. 마음이 쓰이는 점이 있다면 어린이집 선생님과 이야기 나눠 보는 것도 좋아요.',
 'STEADY', 'GENERAL', '근거기반 실제: 부모 실행 중재(Parent-Implemented Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(15, 'GEN-G-01', '잘 되고 있는 것을 이어가요',
 '최근 기록에서 좋은 변화가 보여요. 요즘 새로 해 본 것이 있다면 그게 아이에게 잘 맞는 방법일 수 있어요. 무엇이 달라졌는지 한 줄로 적어 두고 이번 주에도 이어가 보세요. 아이가 잘했을 때는 구체적으로 칭찬해 주세요. "블록 높이 쌓았네!"처럼요.',
 'GROWING', 'GENERAL', '근거기반 실제: 강화(Reinforcement)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(16, 'GEN-A-02', '놀이하며 짧은 말 들려주기',
 '놀이하는 동안 아이가 하는 행동을 짧은 말로 들려주세요. 아이가 블록을 쌓으면 "쌓아", 무너뜨리면 "쿵!"처럼요. 긴 설명보다 한두 단어가 아이 귀에 더 잘 닿아요. 아이에게 따라 하라고 하지 않아도 괜찮아요. 들려주는 것만으로 충분해요.',
 'ANY', 'GENERAL', '근거기반 실제: 모델링(Modeling)의 원리 참고',
 'AFIRM Modules, FPG Child Development Institute', 'https://afirm.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE);
