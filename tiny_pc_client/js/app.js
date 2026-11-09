/**
 * Главное SPA-приложение NodeChain на Mithril.js (Desktop Only).
 * Соответствует строгим правилам fox-codestyle (2 пробела, статика, линейный поток).
 */

// Вспомогательные функции форматирования
class Format {
  static toFoxt(atoms) {
    if (atoms == null) return '0.00000000';
    let b;
    if (typeof atoms === 'bigint') {
      if (atoms <= 0n) return '0.00000000';
      b = atoms;
    } else {
      const n = Number(atoms);
      if (!Number.isFinite(n) || n <= 0) return '0.00000000';
      b = BigInt(Math.trunc(n));
    }
    const whole = b / 100000000n;
    const frac = (b % 100000000n).toString().padStart(8, '0');
    return `${whole}.${frac}`;
  }

  /**
   * Формирует каноническое текстовое представление файла nodewallet.dat (R-16).
   */
  static buildWalletFile(w) {
    if (!w) return '';
    const priv = w.privateKeyBase64 || '';
    const pub = w.publicKeyBase64 || '';
    const addr = w.address || '';
    return `${priv}\n${pub}\n${addr}\n`;
  }

  /**
   * Инициирует скачивание файла nodewallet.dat для кошелька (R-16).
   */
  static downloadWalletFile(w) {
    if (!w) return;
    if (!w.privateKeyBase64) {
      AppState.notify('Приватный ключ защищен WebCrypto (non-extractable). Экспорт доступен только при создании/импорте.', 'error');
      return;
    }
    const content = this.buildWalletFile(w);
    const blob = new Blob([content], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'nodewallet.dat';
    a.click();
    URL.revokeObjectURL(url);
    AppState.notify('Файл nodewallet.dat скачан', 'success');
  }

  // Точный парсинг FOXT в атомы без потери точности IEEE 754 float
  static parseFoxtToAtoms(val) {
    if (val == null) return null;
    const str = String(val).trim();
    if (!str || !/^\d+(\.\d{1,8})?$/.test(str)) return null;
    const [wholeStr, fracStr = ''] = str.split('.');
    const whole = BigInt(wholeStr) * 100000000n;
    const frac = BigInt(fracStr.padEnd(8, '0'));
    const total = whole + frac;
    if (total <= 0n || total > 9007199254740991n) return null;
    return Number(total);
  }

  static shortHash(hash, left = 8, right = 8) {
    if (!hash || hash.length <= (left + right)) return hash || '';
    return `${hash.slice(0, left)}...${hash.slice(-right)}`;
  }

  static date(timestamp) {
    if (!timestamp) return '-';
    const d = new Date(timestamp);
    return d.toLocaleString('ru-RU', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      day: '2-digit',
      month: '2-digit',
      year: 'numeric'
    });
  }

  static async copyText(text, label = 'Скопировано') {
    if (!text || typeof text !== 'string') return;
    const ok = await navigator.clipboard.writeText(text).then(() => true).catch(() => false);
    if (!ok) {
      AppState.notify('Ошибка копирования', 'error');
      return;
    }
    AppState.notify(`${label} в буфер обмена`, 'success');
  }
}

// Локальное состояние модальных окон
const ModalState = {
  isOpen: false,
  title: '',
  type: null, // 'import' | 'export' | 'raw'
  data: null
};

// Компонент шапки (Header)
const HeaderComponent = {
  view() {
    const net = AppState.network;
    const isOnline = AppState.isOnline;

    return m('header.app-header', [
      m('.brand', [
        m('span.brand-icon', '🦊'),
        m('span', 'NodeChain PC'),
        m('span.brand-badge', net.chainId || 'FOX')
      ]),

      m('nav.nav-tabs', [
        m('button.nav-tab', {
          class: AppState.currentTab === 'wallet' ? 'active' : '',
          onclick: () => { AppState.currentTab = 'wallet'; }
        }, [
          m('span.tab-indicator', '💳'),
          m('span', 'Кошелек')
        ]),
        m('button.nav-tab', {
          class: AppState.currentTab === 'explorer' ? 'active' : '',
          onclick: () => {
            AppState.currentTab = 'explorer';
            if (!AppState.explorer.block && !AppState.explorer.tx) AppState.searchExplorer('latest');
          }
        }, [
          m('span.tab-indicator', '🔍'),
          m('span', 'Эксплорер')
        ]),
        m('button.nav-tab', {
          class: AppState.currentTab === 'mempool' ? 'active' : '',
          onclick: () => { AppState.currentTab = 'mempool'; AppState.refreshMempool(); }
        }, [
          m('span.tab-indicator', '⚡'),
          m('span', `Мемпул (${AppState.mempool.count})`)
        ]),
        AppState.isDevMode ? m('button.nav-tab', {
          class: AppState.currentTab === 'dev' ? 'active' : '',
          onclick: () => { AppState.currentTab = 'dev'; }
        }, [
          m('span.tab-indicator', '🛠️'),
          m('span', 'Dev / Управление')
        ]) : null
      ]),

      m('.header-status', [
        m('.node-pill', [
          m('span.status-dot', { class: isOnline ? 'online' : '' }),
          m('span', isOnline ? `Блок #${net.height}` : 'Офлайн'),
          m('span', `| Пиры: ${net.peers}`)
        ]),

        // Переключатель активного кошелька
        AppState.wallets.length > 0 ? m('select.form-control.btn-sm', {
          style: { width: '180px', padding: '4px 8px' },
          value: AppState.activeWallet ? AppState.activeWallet.id : '',
          onchange: (e) => {
            const w = AppState.wallets.find(x => x.id === e.target.value);
            if (w) AppState.setActiveWallet(w);
          }
        }, AppState.wallets.map(w => m('option', { value: w.id }, `${w.label} (${Format.shortHash(w.address, 4, 4)})`))) : null
      ])
    ]);
  }
};

// Компонент вкладки Кошелька
const WalletView = {
  sendForm: {
    to: '',
    amountFoxt: '',
    fee: 50,
    comment: '',
    isSending: false
  },

  async handleSend() {
    const f = WalletView.sendForm;
    if (!AppState.activeWallet || !AppState.activeWallet.cryptoPrivKey) {
      AppState.notify('Приватный ключ активного кошелька не готов', 'error');
      return;
    }
    const cleanTo = (f.to || '').trim().toLowerCase();
    if (!CryptoUtil.isValidAddress(cleanTo)) {
      AppState.notify('Некорректный Fox-адрес получателя (64 символа с f,o,x)', 'error');
      return;
    }

    const amountAtoms = Format.parseFoxtToAtoms(f.amountFoxt);
    if (!amountAtoms) {
      AppState.notify('Укажите корректную сумму отправки (число до 8 знаков после запятой)', 'error');
      return;
    }

    const feeNum = Number(f.fee);
    if (!Number.isSafeInteger(feeNum) || feeNum < 1 || feeNum > 100000000) {
      AppState.notify('Комиссия должна быть целым числом от 1 до 100 000 000 атомов', 'error');
      return;
    }
    const feeAtoms = feeNum;

    const totalRequired = BigInt(amountAtoms) + BigInt(feeAtoms);
    const available = BigInt(Math.max(0, Math.trunc(Number(AppState.activeAccount.unconfirmedBalance) || 0)));
    if (available < totalRequired) {
      AppState.notify(`Недостаточно средств. Доступно: ${Format.toFoxt(available)} FOXT, требуется: ${Format.toFoxt(totalRequired)} FOXT`, 'error');
      return;
    }

    f.isSending = true;
    m.redraw();

    const res = await AppState.buildAndSendTx(AppState.activeWallet, cleanTo, amountAtoms, feeAtoms, f.comment);
    f.isSending = false;

    if (res.isOk) {
      AppState.notify(`Транзакция отправлена в Mempool! Хеш: ${Format.shortHash(res.data.hash, 6, 6)}`, 'success');
      f.to = '';
      f.amountFoxt = '';
      f.comment = '';
      AppState.refreshAccount();
      AppState.refreshHistory();
      AppState.refreshMempool();
    } else {
      AppState.notify(`Ошибка: ${res.error}`, 'error');
    }
    m.redraw();
  },

  view() {
    const w = AppState.activeWallet;
    const acc = AppState.activeAccount;
    const f = WalletView.sendForm;

    if (!w) {
      return m('.card', [
        m('p', 'Нет активного кошелька. Создайте новый кошелек для начала работы.'),
        m('button.btn.btn-primary', { onclick: () => AppState.createNewWallet('Кошелек #1') }, 'Создать кошелек')
      ]);
    }

    return m('div', [
      // Верхняя панель адреса и кнопок
      m('.address-banner', [
        m('div', [
          m('.stat-label', 'Активный Fox-адрес кошелька'),
          m('.address-box', w.address)
        ]),
        m('.btn-group', [
          m('button.btn.btn-sm', { onclick: () => Format.copyText(w.address, 'Адрес') }, '📋 Скопировать'),
          m('button.btn.btn-sm', {
            onclick: () => {
              ModalState.isOpen = true;
              ModalState.title = 'Экспорт кошелька (nodewallet.dat)';
              ModalState.type = 'export';
              ModalState.data = w;
            }
          }, '📤 Экспорт ключей'),
          m('button.btn.btn-sm', {
            onclick: () => {
              ModalState.isOpen = true;
              ModalState.title = 'Импорт существующего ключа';
              ModalState.type = 'import';
              ModalState.data = { priv: '', pub: '', label: 'Импортированный' };
            }
          }, '📥 Импорт'),
          m('button.btn.btn-sm.btn-primary', { onclick: () => AppState.createNewWallet(`Кошелек #${AppState.wallets.length + 1}`) }, '➕ Создать новый')
        ])
      ]),

      // Карточки статистики баланса
      m('.grid-4', [
        m('.stat-box', [
          m('.stat-label', 'Подтвержденный баланс'),
          m('.stat-value', `${Format.toFoxt(acc.balance)} FOXT`),
          m('.stat-sub', `${(Number.isSafeInteger(acc.balance) ? acc.balance : 0).toLocaleString()} атомов`)
        ]),
        m('.stat-box', [
          m('.stat-label', 'Неподтвержденный баланс'),
          m('.stat-value', { style: acc.unconfirmedBalance < acc.balance ? 'color: var(--accent-orange)' : '' },
            `${Format.toFoxt(acc.unconfirmedBalance)} FOXT`),
          m('.stat-sub', acc.unconfirmedBalance < acc.balance ? '⚠️ Есть исходящие в мемпуле' : 'Синхронизировано')
        ]),
        m('.stat-box', [
          m('.stat-label', 'Текущий Nonce'),
          m('.stat-value', acc.nonce),
          m('.stat-sub', 'Счетчик совершенных транзакций')
        ]),
        m('.stat-box', [
          m('.stat-label', 'Всего транзакций'),
          m('.stat-value', acc.txCount),
          m('.stat-sub', [
            m('a', { href: '#', onclick: (e) => { e.preventDefault(); AppState.refreshAccount(); AppState.refreshHistory(); } }, '🔄 Обновить')
          ])
        ])
      ]),

      m('div', { style: { height: '16px' } }),

      // Форма отправки и детали
      m('.grid-2', [
        // Форма отправки
        m('.card', [
          m('.card-title', [
            m('span', '💸 Отправить транзакцию'),
            m('span.badge.badge-green', 'Ed25519 Signed')
          ]),

          m('.form-group', [
            m('label.form-label', 'Адрес получателя (64 hex-символа с f,o,x)'),
            m('input.form-control.mono', {
              placeholder: 'f5ox...',
              value: f.to,
              oninput: (e) => { f.to = e.target.value; }
            })
          ]),

          m('.grid-2', [
            m('.form-group', [
              m('label.form-label', [
                'Сумма (FOXT) ',
                m('a', {
                  href: '#',
                  style: { float: 'right', fontSize: '11px', color: 'var(--accent-blue)' },
                  onclick: (e) => {
                    e.preventDefault();
                    const unconfirmed = BigInt(Math.max(0, Math.trunc(Number(acc.unconfirmedBalance) || 0)));
                    const feeAtoms = BigInt(Math.max(1, Math.trunc(Number(f.fee) || 50)));
                    const maxAtoms = unconfirmed > feeAtoms ? unconfirmed - feeAtoms : 0n;
                    f.amountFoxt = Format.toFoxt(maxAtoms);
                  }
                }, 'Все (MAX)')
              ]),
              m('input.form-control.mono', {
                type: 'number',
                step: '0.00000001',
                placeholder: '0.00000000',
                value: f.amountFoxt,
                oninput: (e) => { f.amountFoxt = e.target.value; }
              })
            ]),

            m('.form-group', [
              m('label.form-label', `Комиссия: ${f.fee} атомов`),
              m('.btn-group', [
                m('button.btn.btn-sm', {
                  class: f.fee === AppState.fees.low ? 'btn-primary' : '',
                  onclick: () => { f.fee = AppState.fees.low; }
                }, `Low (${AppState.fees.low})`),
                m('button.btn.btn-sm', {
                  class: f.fee === AppState.fees.normal ? 'btn-primary' : '',
                  onclick: () => { f.fee = AppState.fees.normal; }
                }, `Normal (${AppState.fees.normal})`),
                m('button.btn.btn-sm', {
                  class: f.fee === AppState.fees.high ? 'btn-primary' : '',
                  onclick: () => { f.fee = AppState.fees.high; }
                }, `High (${AppState.fees.high})`)
              ])
            ])
          ]),

          m('.form-group', [
            m('label.form-label', [
              'Комментарий (опционально, до 256 символов) ',
              m('span', { style: { float: 'right', color: 'var(--text-muted)' } }, `${(f.comment || '').length}/256`)
            ]),
            m('input.form-control', {
              placeholder: 'Например: За перевод или комментарий',
              maxlength: 256,
              value: f.comment,
              oninput: (e) => { f.comment = e.target.value; }
            })
          ]),

          m('button.btn.btn-primary', {
            style: { width: '100%', marginTop: '6px' },
            disabled: f.isSending,
            onclick: WalletView.handleSend
          }, f.isSending ? 'Подписание и отправка...' : '🔐 Подписать Ed25519 и отправить в сеть')
        ]),

        // Карточка параметров безопасности кошелька
        m('.card', [
          m('.card-title', '🛡️ Безопасность и ключи кошелька'),
          m('p', { style: { fontSize: '12px', color: 'var(--text-muted)', marginBottom: '12px' } },
            'Ключи сгенерированы локально через нативный Web Crypto API браузера. Приватный ключ никогда не передается на сервер ноды.'),

          m('.form-group', [
            m('label.form-label', 'Открытый ключ (X.509 SPKI Base64)'),
            m('textarea.form-control', { rows: 3, readonly: true, value: w.publicKeyBase64 })
          ]),

          m('.form-group', [
            m('label.form-label', 'Формат кошелька ноды nodewallet.dat'),
            m('p', { style: { fontSize: '11px', color: 'var(--text-muted)', marginBottom: '8px' } },
              'Вы можете скачать ключ в формате nodewallet.dat и положить в рабочую папку NodeChain для майнинга прямо на этот кошелек:'),
            m('button.btn.btn-sm', {
              onclick: () => Format.downloadWalletFile(w)
            }, '💾 Скачать nodewallet.dat')
          ])
        ])
      ]),

      // История транзакций
      m('.card', [
        m('.card-title', [
          m('span', `📜 История транзакций адреса (${AppState.history.total})`),
          m('.btn-group', [
            m('button.btn.btn-sm', {
              disabled: AppState.history.page <= 0 || AppState.history.isLoading,
              onclick: () => AppState.refreshHistory(AppState.history.page - 1)
            }, '« Назад'),
            m('span', { style: { alignSelf: 'center', fontSize: '11px', padding: '0 6px' } },
              `Стр. ${AppState.history.page + 1} из ${Math.max(1, Math.ceil(AppState.history.total / AppState.history.limit))}`),
            m('button.btn.btn-sm', {
              disabled: ((AppState.history.page + 1) * AppState.history.limit) >= AppState.history.total || AppState.history.isLoading,
              onclick: () => AppState.refreshHistory(AppState.history.page + 1)
            }, 'Вперед »')
          ])
        ]),

        m('.table-wrap', [
          m('table', [
            m('thead', [
              m('tr', [
                m('th', 'Статус'),
                m('th', 'Тип'),
                m('th', 'Хеш транзакции'),
                m('th', 'Сумма (FOXT)'),
                m('th', 'Комиссия'),
                m('th', 'Комментарий'),
                m('th', 'Подтверждений'),
                m('th', 'Время')
              ])
            ]),
            m('tbody', [
              AppState.history.items.length === 0
                ? m('tr', [m('td', { colspan: 8, style: { textAlign: 'center', padding: '24px', color: 'var(--text-muted)' } }, 'Транзакций пока нет')])
                : AppState.history.items.map(tx => {
                  const isOutgoing = (tx.from || '').toLowerCase() === (w.address || '').toLowerCase();
                  const statusClass = tx.status === 'confirmed' ? 'badge-green' : (tx.status === 'pending' ? 'badge-yellow' : 'badge-red');
                  return m('tr', [
                    m('td', m('span.badge', { class: statusClass }, tx.status)),
                    m('td', isOutgoing ? m('span', { style: 'color: var(--accent-red)' }, '🔴') : m('span', { style: 'color: var(--accent-green)' }, '🟢')),
                    m('td.mono', [
                      m('a', {
                        href: '#',
                        style: { color: 'var(--accent-blue)', textDecoration: 'none' },
                        onclick: (e) => {
                          e.preventDefault();
                          AppState.currentTab = 'explorer';
                          AppState.searchExplorer(tx.hash);
                        }
                      }, Format.shortHash(tx.hash, 8, 8))
                    ]),
                    m('td.mono', `${Format.toFoxt(tx.amount)}`),
                    m('td.mono', `${tx.fee}`),
                    m('td', [
                      tx.comment
                        ? m('span', {
                          title: tx.comment,
                          style: {
                            color: 'var(--text-bright)',
                            maxWidth: '160px',
                            display: 'inline-block',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            verticalAlign: 'middle'
                          }
                        }, tx.comment)
                        : m('span', { style: { color: 'var(--text-muted)' } }, '—')
                    ]),
                    m('td', tx.confirmations != null ? tx.confirmations : '-'),
                    m('td', Format.date(tx.timestamp))
                  ]);
                })
            ])
          ])
        ])
      ])
    ]);
  }
};

// Компонент вкладки Эксплорера
const ExplorerView = {
  view() {
    const ex = AppState.explorer;

    return m('div', [
      m('.card', [
        m('.card-title', '🔍 Поиск по блокчейну NodeChain'),
        m('div', { style: { display: 'flex', gap: '8px' } }, [
          m('input.form-control.mono', {
            placeholder: 'Введите номер блока (например: 0, 1), хеш блока или хеш транзакции...',
            value: ex.query,
            oninput: (e) => { ex.query = e.target.value; },
            onkeydown: (e) => { if (e.key === 'Enter') AppState.searchExplorer(ex.query); }
          }),
          m('button.btn.btn-primary', {
            disabled: ex.isLoading,
            onclick: () => AppState.searchExplorer(ex.query)
          }, ex.isLoading ? 'Поиск...' : 'Найти')
        ]),
        ex.error ? m('p', { style: { color: 'var(--accent-red)', marginTop: '8px', fontSize: '12px' } }, ex.error) : null
      ]),

      // Просмотр найденного блока
      ex.block ? m('.card', [
        m('.card-title', [
          m('span', `📦 Блок #${ex.block.height}`),
          m('.btn-group', [
            m('button.btn.btn-sm', {
              disabled: ex.block.height <= 0,
              onclick: () => AppState.searchExplorer((ex.block.height - 1).toString())
            }, '« Пред. блок'),
            m('button.btn.btn-sm', {
              onclick: () => AppState.searchExplorer((ex.block.height + 1).toString())
            }, 'След. блок »')
          ])
        ]),

        m('.grid-2', [
          m('.stat-box', [
            m('.stat-label', 'Хеш блока (SHA-256)'),
            m('.stat-value', { style: { fontSize: '12px', wordBreak: 'break-all' } }, ex.block.hash)
          ]),
          m('.stat-box', [
            m('.stat-label', 'Хеш предыдущего блока'),
            m('.stat-value', { style: { fontSize: '12px', wordBreak: 'break-all' } }, ex.block.previousHash)
          ])
        ]),

        m('div', { style: { height: '10px' } }),

        m('.grid-4', [
          m('.stat-box', [
            m('.stat-label', 'Транзакций в блоке'),
            m('.stat-value', ex.block.txCount)
          ]),
          m('.stat-box', [
            m('.stat-label', 'Proof-of-Work Nonce'),
            m('.stat-value', ex.block.nonce || 0)
          ]),
          m('.stat-box', [
            m('.stat-label', 'Майнер (награда за блок)'),
            m('.stat-value', { style: { fontSize: '11px' } },
              ex.block.minerAddress ? Format.shortHash(ex.block.minerAddress, 6, 6) : '-')
          ]),
          m('.stat-box', [
            m('.stat-label', 'Время блока'),
            m('.stat-value', { style: { fontSize: '12px' } }, Format.date(ex.block.timestamp))
          ])
        ]),

        m('div', { style: { height: '12px' } }),
        m('.stat-label', 'Корень дерева Меркла (Merkle Root)'),
        m('.address-box', { style: { fontSize: '12px' } }, ex.block.merkleRoot),

        ex.block.transactions && ex.block.transactions.length > 0 ? m('div', { style: { marginTop: '14px' } }, [
          m('.stat-label', 'Хеши включенных транзакций:'),
          m('ul', { style: { paddingLeft: '20px', fontSize: '12px', color: 'var(--accent-blue)' } },
            ex.block.transactions.map(txHash => m('li.mono', [
              m('a', {
                href: '#',
                style: { color: 'var(--accent-blue)', textDecoration: 'none' },
                onclick: (e) => { e.preventDefault(); AppState.searchExplorer(txHash); }
              }, txHash)
            ]))
          )
        ]) : m('p', { style: { marginTop: '10px', color: 'var(--text-muted)', fontSize: '12px' } }, 'В блоке нет пользовательских транзакций (пустой блок).')
      ]) : null,

      // Просмотр найденной транзакции
      ex.tx ? m('.card', [
        m('.card-title', [
          m('span', '📝 Транзакция'),
          m('span.badge', { class: ex.tx.status === 'confirmed' ? 'badge-green' : 'badge-yellow' }, ex.tx.status)
        ]),

        m('.grid-2', [
          m('.stat-box', [
            m('.stat-label', 'Отправитель (From)'),
            m('.stat-value', { style: { fontSize: '11px', wordBreak: 'break-all' } }, ex.tx.from)
          ]),
          m('.stat-box', [
            m('.stat-label', 'Получатель (To)'),
            m('.stat-value', { style: { fontSize: '11px', wordBreak: 'break-all' } }, ex.tx.to)
          ])
        ]),

        m('div', { style: { height: '10px' } }),

        m('.grid-4', [
          m('.stat-box', [
            m('.stat-label', 'Сумма'),
            m('.stat-value', `${Format.toFoxt(ex.tx.amount)} FOXT`)
          ]),
          m('.stat-box', [
            m('.stat-label', 'Комиссия'),
            m('.stat-value', `${ex.tx.fee} атомов`)
          ]),
          m('.stat-box', [
            m('.stat-label', 'Блок'),
            m('.stat-value', ex.tx.blockHeight != null ? `#${ex.tx.blockHeight}` : 'В мемпуле')
          ]),
          m('.stat-box', [
            m('.stat-label', 'Подтверждений'),
            m('.stat-value', ex.tx.confirmations != null ? ex.tx.confirmations : '0')
          ])
        ]),

        m('div', { style: { marginTop: '12px' } }, [
          m('.stat-label', 'Комментарий к транзакции:'),
          m('.comment-box', {
            style: {
              color: ex.tx.comment ? 'var(--text-bright)' : 'var(--text-muted)',
              fontSize: '13px',
              fontStyle: ex.tx.comment ? 'normal' : 'italic',
              wordBreak: 'break-all',
              overflowWrap: 'anywhere',
              whiteSpace: 'pre-wrap'
            }
          }, ex.tx.comment ? `💬 ${ex.tx.comment}` : 'Без комментария (не указан)')
        ]),

        m('div', { style: { marginTop: '14px' } }, [
          m('.stat-label', 'Полный JSON объект:'),
          m('.json-box', JSON.stringify(ex.tx, null, 2))
        ])
      ]) : null
    ]);
  }
};

// Компонент вкладки Мемпула
const MempoolView = {
  view() {
    const mp = AppState.mempool;

    return m('div', [
      m('.card', [
        m('.card-title', [
          m('span', `⚡ Текущий Mempool очереди (${mp.count} транзакций)`),
          m('.btn-group', [
            m('label', { style: { fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px' } }, [
              m('input[type=checkbox]', {
                checked: mp.isAutoRefresh,
                onchange: (e) => { mp.isAutoRefresh = e.target.checked; }
              }),
              'Автообновление (каждые 4с)'
            ]),
            m('button.btn.btn-sm', { onclick: () => AppState.refreshMempool() }, '🔄 Обновить')
          ])
        ]),

        m('p', { style: { fontSize: '12px', color: 'var(--text-muted)', marginBottom: '12px' } },
          'Транзакции в пуле ожидают включения в блок майнером. Фоновый авто-майнер NodeChain опрашивает мемпул каждые 10 секунд.'),

        m('.table-wrap', [
          m('table', [
            m('thead', [
              m('tr', [
                m('th', 'Хеш'),
                m('th', 'Отправитель'),
                m('th', 'Получатель'),
                m('th', 'Сумма (FOXT)'),
                m('th', 'Комиссия'),
                m('th', 'Nonce'),
                m('th', 'Комментарий')
              ])
            ]),
            m('tbody', [
              mp.items.length === 0
                ? m('tr', [m('td', { colspan: 7, style: { textAlign: 'center', padding: '24px', color: 'var(--text-muted)' } }, 'Мемпул пуст. Все транзакции смайнены в блоки!')])
                : mp.items.map(tx => m('tr', [
                  m('td.mono', Format.shortHash(tx.hash, 6, 6)),
                  m('td.mono', Format.shortHash(tx.from, 6, 6)),
                  m('td.mono', Format.shortHash(tx.to, 6, 6)),
                  m('td.mono', Format.toFoxt(tx.amount)),
                  m('td.mono', tx.fee),
                  m('td', tx.nonce),
                  m('td', [
                    tx.comment
                      ? m('span', {
                        title: tx.comment,
                        style: {
                          color: 'var(--text-bright)',
                          maxWidth: '160px',
                          display: 'inline-block',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                          verticalAlign: 'middle'
                        }
                      }, tx.comment)
                      : m('span', { style: { color: 'var(--text-muted)' } }, '—')
                  ])
                ]))
            ])
          ])
        ])
      ])
    ]);
  }
};

// Компонент вкладки Разработчика / Dev Section
const DevView = {
  async handleSendFaucet() {
    const dev = AppState.dev;
    if (!dev.minerWallet || !dev.minerWallet.cryptoPrivKey) {
      AppState.notify('Ключ майнера/крана не загружен', 'error');
      return;
    }
    const targetAddr = (dev.faucetRecipient || (AppState.activeWallet ? AppState.activeWallet.address : '')).trim().toLowerCase();
    if (!CryptoUtil.isValidAddress(targetAddr)) {
      AppState.notify('Укажите корректный Fox-адрес получателя', 'error');
      return;
    }

    const amountAtoms = Format.parseFoxtToAtoms(dev.faucetAmount || '1');
    if (!amountAtoms) {
      AppState.notify('Укажите корректную сумму крана', 'error');
      return;
    }
    const feeNum = Number(dev.faucetFee);
    const feeAtoms = (Number.isSafeInteger(feeNum) && feeNum >= 1 && feeNum <= 100000000) ? feeNum : 50;

    dev.isProcessing = true;
    m.redraw();

    const sendRes = await AppState.buildAndSendTx(dev.minerWallet, targetAddr, amountAtoms, feeAtoms, 'Dev Faucet Drop');
    dev.isProcessing = false;

    if (sendRes.isOk) {
      AppState.notify(`Успешно выдано ${dev.faucetAmount} FOXT! Транзакция в мемпуле.`, 'success');
      AppState.refreshMinerAccount();
      AppState.refreshAccount();
      AppState.refreshMempool();
    } else {
      AppState.notify(`Ошибка выдачи: ${sendRes.error}`, 'error');
    }
    m.redraw();
  },

  async handleBatchSpam(count = 3) {
    if (!AppState.dev.minerWallet || !AppState.dev.minerWallet.cryptoPrivKey) {
      AppState.notify('Сначала загрузите кошелек майнера/крана', 'error');
      return;
    }
    if (!AppState.activeWallet || !AppState.activeWallet.address) {
      AppState.notify('Сначала выберите или создайте активный кошелек получателя', 'error');
      return;
    }
    const minerAddr = AppState.dev.minerWallet.address.toLowerCase();
    const recipient = AppState.activeWallet.address.toLowerCase();
    if (minerAddr === recipient) {
      AppState.notify('Нельзя отправить средства самому себе (выберите другой активный кошелек)', 'error');
      return;
    }
    let curNonce = await AppState.getNextNonce(minerAddr);
    if (curNonce == null) {
      AppState.notify('Не удалось определить nonce майнера', 'error');
      return;
    }

    AppState.notify(`Генерация ${count} транзакций с последовательным nonce...`, 'info');

    let sentCount = 0;
    for (let i = 0; i < count; i++) {
      const res = await AppState.buildAndSendTx(
        AppState.dev.minerWallet,
        recipient,
        1000,
        50,
        `Batch Test #${i + 1}`,
        curNonce
      );
      if (!res.isOk) break;
      curNonce++;
      sentCount++;
    }

    AppState.notify(`${sentCount} тестовых транзакций отправлены в Mempool!`, sentCount > 0 ? 'success' : 'error');
    AppState.refreshMempool();
    AppState.refreshMinerAccount();
    AppState.refreshAccount();
    AppState.refreshHistory();
  },

  async executeRawRpc() {
    const raw = AppState.dev.rawRpc;
    raw.isLoading = true;
    raw.result = null;
    m.redraw();

    let bodyObj = null;
    if (raw.body && raw.body.trim().length > 0) {
      bodyObj = AppState.parseJsonSafe(raw.body);
      if (bodyObj == null) {
        raw.isLoading = false;
        raw.result = { error: 'Некорректный JSON в теле запроса' };
        m.redraw();
        return;
      }
    }

    const res = await NodeApi.request(raw.method, raw.path, bodyObj);
    raw.isLoading = false;
    raw.result = res;
    m.redraw();
  },

  view() {
    const dev = AppState.dev;

    return m('div', [
      // Настройка RPC ноды
      m('.card', [
        m('.card-title', '⚙️ Подключение к узлу NodeChain'),
        m('.grid-2', [
          m('.form-group', [
            m('label.form-label', 'Адрес REST API ноды (RPC Endpoint)'),
            m('input.form-control.mono', {
              value: AppState.rpcInput,
              onchange: (e) => { AppState.setRpcUrl(e.target.value); },
              oninput: (e) => { AppState.rpcInput = e.target.value; }
            })
          ]),
          m('.form-group', [
            m('label.form-label', 'Действие'),
            m('button.btn.btn-primary', {
              style: { marginTop: '2px' },
              onclick: () => {
                if (!AppState.rpcInput) return;
                const isUpdated = AppState.setRpcUrl(AppState.rpcInput);
                if (isUpdated) {
                  AppState.refreshNetwork();
                  AppState.notify('Адрес узла обновлен. Проверяем связь...', 'info');
                } else {
                  AppState.notify('Некорректный адрес узла (пример: http://localhost:8080)', 'error');
                }
              }
            }, '🔄 Применить и проверить')
          ])
        ]),

        m('.grid-4', [
          m('.stat-box', [
            m('.stat-label', 'Статус ноды'),
            m('.stat-value', { style: AppState.isOnline ? 'color: var(--accent-green)' : 'color: var(--accent-red)' },
              AppState.isOnline ? 'ONLINE' : 'OFFLINE')
          ]),
          m('.stat-box', [
            m('.stat-label', 'Высота цепи'),
            m('.stat-value', AppState.network.height)
          ]),
          m('.stat-box', [
            m('.stat-label', 'Активные P2P пиры'),
            m('.stat-value', AppState.network.peers)
          ]),
          m('.stat-box', [
            m('.stat-label', 'Синхронизация'),
            m('.stat-value', AppState.network.isSynced ? 'Да' : 'В процессе')
          ])
        ])
      ]),

      // Кран / Выдача монет (Faucet & Miner Wallet)
      m('.card', [
        m('.card-title', [
          m('span', '🪙 Выдача монет (Dev Faucet / Эмиссия)'),
          m('span.badge.badge-yellow', 'Dev Tool')
        ]),

        m('p', { style: { fontSize: '12px', color: 'var(--text-muted)', marginBottom: '14px' } },
          'В архитектуре NodeChain новые монеты создаются исключительно в качестве награды за блок майнеру (файл nodewallet.dat в корне сервера). Чтобы выдать монеты, вставьте ключ майнера из nodewallet.dat:'),

        !dev.minerWallet ? m('.card', { style: { backgroundColor: 'var(--bg-input)' } }, [
          m('.form-group', [
            m('label.form-label', 'Вставьте содержимое файла nodewallet.dat или приватный ключ (строка 1):'),
            m('textarea.form-control.mono', {
              rows: 3,
              placeholder: 'MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC...\n(или все 3 строки из nodewallet.dat)',
              value: dev.inputContent || '',
              oninput: (e) => { dev.inputContent = e.target.value; }
            })
          ]),
          m('button.btn.btn-orange', {
            onclick: async () => {
              if (!dev.inputContent || !dev.inputContent.trim()) return;
              const parsed = AppState.parseWalletInput(dev.inputContent);
              if (!parsed) {
                AppState.notify('Не удалось расшифровать ключи nodewallet.dat (нужны приватный и открытый ключи)', 'error');
                return;
              }

              const imported = await CryptoUtil.loadWalletFromData(parsed.priv, parsed.pub, parsed.addr);
              if (!imported) {
                AppState.notify('Не удалось расшифровать ключи nodewallet.dat (нужны приватный и открытый ключи)', 'error');
                return;
              }
              dev.minerWallet = {
                address: imported.address,
                privateKeyBase64: imported.privateKeyBase64,
                publicKeyBase64: imported.publicKeyBase64,
                cryptoPrivKey: imported.privateKey,
                cryptoPubKey: imported.publicKey
              };
              await AppState.saveDevWallet(dev.minerWallet);
              AppState.refreshMinerAccount();
              dev.inputContent = '';
              AppState.notify('Кошелек майнера подключен к крану!', 'success');
              m.redraw();
            }
          }, '🔑 Подключить кошелек майнера к Faucet')
        ]) : m('div', [
          m('.grid-2', [
            m('.stat-box', [
              m('.stat-label', 'Адрес майнера крана'),
              m('.stat-value', { style: { fontSize: '11px', wordBreak: 'break-all' } }, dev.minerWallet.address)
            ]),
            m('.stat-box', [
              m('.stat-label', 'Баланс крана'),
              m('.stat-value', dev.minerAccount ? `${Format.toFoxt(dev.minerAccount.balance)} FOXT` : '0 FOXT'),
              m('.stat-sub', dev.minerAccount ? `Nonce: ${dev.minerAccount.nonce}` : '')
            ])
          ]),

          m('div', { style: { height: '14px' } }),

          m('.grid-3', [
            m('.form-group', [
              m('label.form-label', 'Кому выдать (получатель)'),
              m('input.form-control.mono', {
                placeholder: 'Fox-адрес получателя...',
                value: dev.faucetRecipient || (AppState.activeWallet ? AppState.activeWallet.address : ''),
                oninput: (e) => { dev.faucetRecipient = e.target.value; }
              })
            ]),
            m('.form-group', [
              m('label.form-label', 'Количество (FOXT)'),
              m('input.form-control.mono', {
                type: 'number',
                value: dev.faucetAmount,
                oninput: (e) => { dev.faucetAmount = e.target.value; }
              })
            ]),
            m('.form-group', [
              m('label.form-label', 'Действие'),
              m('button.btn.btn-orange', {
                style: { width: '100%', marginTop: '2px' },
                disabled: dev.isProcessing,
                onclick: DevView.handleSendFaucet
              }, dev.isProcessing ? 'Отправка...' : '⚡ Выдать монеты')
            ])
          ]),

          m('.btn-group', [
            m('button.btn.btn-sm', { onclick: () => DevView.handleBatchSpam(3) }, '🚀 Создать 3 тестовые транзакции (Stress Mempool)'),
            m('button.btn.btn-sm', {
              onclick: async () => {
                dev.minerWallet = null;
                localStorage.removeItem(AppState.STORAGE_KEY_DEV_WALLET);
                await KeyVault.remove('__dev_miner_wallet__');
                AppState.notify('Кошелек майнера отключен', 'info');
              }
            }, 'Отключить кошелек крана')
          ])
        ])
      ]),

      // Консоль Raw RPC
      m('.card', [
        m('.card-title', '📡 Raw RPC Консоль запросов к ноде'),
        m('.grid-3', [
          m('.form-group', [
            m('label.form-label', 'HTTP Метод'),
            m('select.form-control', {
              value: dev.rawRpc.method,
              onchange: (e) => { dev.rawRpc.method = e.target.value; }
            }, [
              m('option', 'GET'),
              m('option', 'POST')
            ])
          ]),
          m('.form-group', { style: { gridColumn: 'span 2' } }, [
            m('label.form-label', 'Путь эндпоинта'),
            m('input.form-control.mono', {
              value: dev.rawRpc.path,
              oninput: (e) => { dev.rawRpc.path = e.target.value; }
            })
          ])
        ]),

        dev.rawRpc.method === 'POST' ? m('.form-group', [
          m('label.form-label', 'JSON Тело запроса:'),
          m('textarea.form-control.mono', {
            rows: 3,
            value: dev.rawRpc.body,
            oninput: (e) => { dev.rawRpc.body = e.target.value; }
          })
        ]) : null,

        m('.btn-group', { style: { marginBottom: '12px' } }, [
          m('button.btn.btn-sm', { onclick: () => { dev.rawRpc.path = '/api/v1/network'; dev.rawRpc.method = 'GET'; } }, '/network'),
          m('button.btn.btn-sm', { onclick: () => { dev.rawRpc.path = '/api/v1/blocks/latest'; dev.rawRpc.method = 'GET'; } }, '/blocks/latest'),
          m('button.btn.btn-sm', { onclick: () => { dev.rawRpc.path = '/api/v1/transactions/mempool'; dev.rawRpc.method = 'GET'; } }, '/transactions/mempool'),
          m('button.btn.btn-sm', { onclick: () => { dev.rawRpc.path = '/api/v1/transactions/fee'; dev.rawRpc.method = 'GET'; } }, '/transactions/fee'),
          m('button.btn.btn-primary.btn-sm', {
            disabled: dev.rawRpc.isLoading,
            onclick: DevView.executeRawRpc
          }, dev.rawRpc.isLoading ? 'Выполнение...' : '▶ Выполнить запрос')
        ]),

        dev.rawRpc.result ? m('.json-box', JSON.stringify(dev.rawRpc.result, null, 2)) : null
      ])
    ]);
  }
};

// Компонент модального окна
const ModalComponent = {
  view() {
    if (!ModalState.isOpen) return null;

    return m('.modal-overlay', {
      onclick: (e) => {
        if (e.target.classList.contains('modal-overlay')) ModalState.isOpen = false;
      }
    }, [
      m('.modal-content', [
        m('.modal-header', [
          m('span', { style: { fontWeight: 600 } }, ModalState.title),
          m('button.btn.btn-sm', { onclick: () => { ModalState.isOpen = false; } }, '✕')
        ]),
        m('.modal-body', [
          ModalState.type === 'export' ? m('div', [
            m('.form-group', [
              m('label.form-label', 'Fox-адрес кошелька:'),
              m('.address-box', ModalState.data.address)
            ]),
            ModalState.data.privateKeyBase64 ? m('.form-group', [
              m('label.form-label', 'Закрытый ключ (PKCS#8 Base64) - ХРАНИТЕ В ТАЙНЕ:'),
              m('textarea.form-control.mono', { rows: 4, readonly: true, value: ModalState.data.privateKeyBase64 })
            ]) : m('.stat-box', { style: { marginBottom: '12px' } }, [
              m('.stat-label', 'Безопасность закрытого ключа'),
              m('p', { style: { fontSize: '12px', color: 'var(--text-muted)' } },
                '🔒 Ключ сохранен в защищенном хранилище как non-extractable CryptoKey. Текстовый приватный ключ не хранится в браузере.')
            ]),
            m('.form-group', [
              m('label.form-label', 'Открытый ключ (X.509 SPKI Base64):'),
              m('textarea.form-control.mono', { rows: 3, readonly: true, value: ModalState.data.publicKeyBase64 })
            ]),
            ModalState.data.privateKeyBase64 ? m('button.btn.btn-primary.btn-sm', {
              onclick: () => Format.downloadWalletFile(ModalState.data)
            }, '💾 Скачать nodewallet.dat') : null
          ]) : null,

          ModalState.type === 'import' ? m('div', [
            m('.form-group', [
              m('label.form-label', 'Имя / Метка кошелька:'),
              m('input.form-control', {
                value: ModalState.data.label,
                oninput: (e) => { ModalState.data.label = e.target.value; }
              })
            ]),
            m('.form-group', [
              m('label.form-label', 'Закрытый ключ (PKCS#8 Base64) или все 3 строки nodewallet.dat:'),
              m('textarea.form-control.mono', {
                rows: 4,
                placeholder: 'Вставьте PKCS#8 ключ или все 3 строки из nodewallet.dat...',
                value: ModalState.data.priv,
                oninput: (e) => { ModalState.data.priv = e.target.value; }
              })
            ]),
            m('.form-group', [
              m('label.form-label', 'Открытый ключ (X.509 Base64, обязателен если не вставлен nodewallet.dat):'),
              m('textarea.form-control.mono', {
                rows: 3,
                placeholder: 'Вставьте X.509 ключ...',
                value: ModalState.data.pub,
                oninput: (e) => { ModalState.data.pub = e.target.value; }
              })
            ])
          ]) : null
        ]),
        m('.modal-footer', [
          m('button.btn', { onclick: () => { ModalState.isOpen = false; } }, 'Закрыть'),
          ModalState.type === 'import' ? m('button.btn.btn-primary', {
            onclick: async () => {
              const res = await AppState.importWallet(ModalState.data.priv, ModalState.data.pub, ModalState.data.label);
              if (res) ModalState.isOpen = false;
            }
          }, 'Импортировать') : null
        ])
      ])
    ]);
  }
};

// Компонент уведомлений
const ToastComponent = {
  view() {
    if (!AppState.toast.isVisible) return null;
    return m('.toast-container', [
      m('.toast', { class: AppState.toast.type }, [
        m('span', AppState.toast.type === 'success' ? '✅' : (AppState.toast.type === 'error' ? '❌' : 'ℹ️')),
        m('span', AppState.toast.message)
      ])
    ]);
  }
};

// Корневое приложение
const App = {
  oninit() {
    AppState.init();
  },
  view() {
    return m('div', [
      m(HeaderComponent),
      m('main.app-container', [
        AppState.currentTab === 'wallet' ? m(WalletView) : null,
        AppState.currentTab === 'explorer' ? m(ExplorerView) : null,
        AppState.currentTab === 'mempool' ? m(MempoolView) : null,
        AppState.isDevMode && AppState.currentTab === 'dev' ? m(DevView) : null
      ]),
      m(ModalComponent),
      m(ToastComponent)
    ]);
  }
};

// Запуск приложения
document.addEventListener('DOMContentLoaded', () => {
  const root = document.getElementById('app');
  if (root) m.mount(root, App);
});

