const OCR_SCRIPT_URL = 'https://cdn.jsdelivr.net/npm/tesseract.js@6.0.1/dist/tesseract.min.js';

const $ = (selector) => document.querySelector(selector);
const cameraInput = $('#camera-input');
const galleryInput = $('#gallery-input');
const uploadState = $('#upload-state');
const previewState = $('#preview-state');
const preview = $('#receipt-preview');
const scanActions = $('#scan-actions');
const scanButton = $('#scan-button');
const progressCard = $('#progress-card');
const progressLabel = $('#progress-label');
const progressPercent = $('#progress-percent');
const progressFill = $('#progress-fill');
const errorMessage = $('#error-message');
const resultCard = $('#result-card');
const amountInput = $('#amount-input');
const resultTitle = $('#result-title');
const resultHint = $('#result-hint');
const matchBadge = $('#match-badge');
const candidateArea = $('#candidate-area');
const candidateList = $('#candidate-list');
const sourceLine = $('#source-line');
const sourceText = $('#source-text');
const ocrDetails = $('#ocr-details');
const ocrText = $('#ocr-text');
const copyFeedback = $('#copy-feedback');

let selectedFile = null;
let previewUrl = null;
let workerPromise = null;

const moneyPattern = /(?:€\s*)?(?:(?:\d{1,3}(?:[.\s]\d{3})+|\d+)[,.]\d{2})(?:\s*(?:€|EUR))?/gi;
const totalLabelPattern = /\b(?:zu\s*zahlen|zahl(?:ungs)?betrag|endbetrag|gesamt(?:betrag|summe|total)?|summe|total|amount\s*due|rechnungsbetrag|balance\s*due)\b/i;
const excludeLabelPattern = /\b(?:mwst|ust|steuer|vat|tax|netto|rabatt|gutschein|pfand|r[uü]ckgeld|wechselgeld|gegeben|subtotal|unter\s*summe|zwischensumme|kreditkarte|ec\s*karte|visa|mastercard|barzahlung)\b/i;

$('#camera-button').addEventListener('click', () => cameraInput.click());
$('#gallery-button').addEventListener('click', () => galleryInput.click());
$('#replace-button').addEventListener('click', () => galleryInput.click());
$('#new-scan-button').addEventListener('click', resetScanner);
$('#copy-button').addEventListener('click', copyAmount);
scanButton.addEventListener('click', scanReceipt);

for (const input of [cameraInput, galleryInput]) {
  input.addEventListener('change', () => {
    const [file] = input.files ?? [];
    input.value = '';
    if (file) setReceipt(file);
  });
}

amountInput.addEventListener('input', () => {
  copyFeedback.textContent = '';
});

if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost')) {
  navigator.serviceWorker.register('./sw.js').catch(() => {});
}

function setReceipt(file) {
  if (!file.type.startsWith('image/')) {
    showError('Bitte wähle ein Bild deines Kassenbons aus.');
    return;
  }

  clearObjectUrl();
  selectedFile = file;
  previewUrl = URL.createObjectURL(file);
  preview.src = previewUrl;
  $('#file-name').textContent = file.name || 'Kassenbon-Foto';
  $('#file-size').textContent = formatFileSize(file.size);
  uploadState.hidden = true;
  previewState.hidden = false;
  scanActions.hidden = false;
  progressCard.hidden = true;
  resultCard.hidden = true;
  errorMessage.hidden = true;
  scanButton.disabled = false;
  scanButton.innerHTML = '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 5h4l1.4-2h3.2L13 5h4v12H3V5Z"/><circle cx="10" cy="11" r="3.2"/><path d="M14.7 8.1h.01"/></svg>Summe erkennen<svg class="button-arrow" viewBox="0 0 20 20" aria-hidden="true"><path d="M4 10h11M10 5l5 5-5 5"/></svg>';
  copyFeedback.textContent = '';
}

async function scanReceipt() {
  if (!selectedFile) return;

  errorMessage.hidden = true;
  resultCard.hidden = true;
  scanButton.disabled = true;
  progressCard.hidden = false;
  updateProgress('Starte die Texterkennung …', 2);

  try {
    const image = await prepareImage(selectedFile);
    const worker = await getOcrWorker();
    const result = await worker.recognize(image);
    const text = result.data.text ?? '';
    const confidence = Number(result.data.confidence) || 0;
    const candidates = findCandidates(text);
    showResult(text, confidence, candidates);
  } catch (error) {
    const message = error instanceof Error ? error.message : '';
    if (/fetch|network|load|wasm|worker|language|traineddata/i.test(message)) {
      showError('Die Texterkennung konnte nicht geladen werden. Prüfe deine Internetverbindung und versuche es noch einmal.');
    } else {
      showError('Der Bon konnte nicht gelesen werden. Wähle ein schärferes Foto oder fotografiere ihn noch einmal.');
    }
  } finally {
    progressCard.hidden = true;
    scanButton.disabled = false;
  }
}

function loadOcrScript() {
  if (window.Tesseract) return Promise.resolve(window.Tesseract);

  return new Promise((resolve, reject) => {
    const existing = document.querySelector('script[data-tesseract]');
    if (existing) {
      existing.addEventListener('load', () => resolve(window.Tesseract), { once: true });
      existing.addEventListener('error', () => reject(new Error('OCR library could not be loaded')), { once: true });
      return;
    }

    const script = document.createElement('script');
    script.src = OCR_SCRIPT_URL;
    script.async = true;
    script.dataset.tesseract = 'true';
    script.onload = () => resolve(window.Tesseract);
    script.onerror = () => {
      script.remove();
      reject(new Error('OCR library could not be loaded'));
    };
    document.head.append(script);
  });
}

async function getOcrWorker() {
  const library = await loadOcrScript();
  if (!library) throw new Error('OCR library is unavailable');

  if (!workerPromise) {
    workerPromise = library.createWorker('deu+eng', 1, {
      logger: (progress) => {
        const statusNames = {
          'loading tesseract core': 'Lade OCR-Engine …',
          'initializing tesseract': 'Initialisiere OCR-Engine …',
          'loading language traineddata': 'Lade Sprachmodell …',
          'initializing api': 'Bereite Texterkennung vor …',
          'recognizing text': 'Lese den Kassenbon …',
        };
        const label = statusNames[progress.status] ?? 'Lese den Kassenbon …';
        const percent = Math.round((progress.progress ?? 0) * 100);
        updateProgress(label, percent);
      },
    }).catch((error) => {
      workerPromise = null;
      throw error;
    });
  }

  return workerPromise;
}

async function prepareImage(file) {
  let source;
  if ('createImageBitmap' in window) {
    source = await createImageBitmap(file).catch(() => loadImage(previewUrl));
  } else {
    source = await loadImage(previewUrl);
  }

  const maxDimension = 2600;
  const scale = Math.min(1, maxDimension / Math.max(source.width, source.height));
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, Math.round(source.width * scale));
  canvas.height = Math.max(1, Math.round(source.height * scale));
  const context = canvas.getContext('2d', { alpha: false });
  if (!context) throw new Error('Could not prepare receipt image');
  context.fillStyle = '#fff';
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.drawImage(source, 0, 0, canvas.width, canvas.height);
  if ('close' in source && typeof source.close === 'function') source.close();
  return canvas;
}

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('Could not read receipt image'));
    image.src = url;
  });
}

function findCandidates(text) {
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  const labelled = [];
  const fallback = [];

  lines.forEach((line, index) => {
    moneyPattern.lastIndex = 0;
    const matches = [...line.matchAll(moneyPattern)];
    if (!matches.length) return;

    const normalizedLine = line.toLocaleLowerCase('de-DE');
    const isLabelled = totalLabelPattern.test(normalizedLine);
    const isExcluded = excludeLabelPattern.test(normalizedLine);
    const position = lines.length > 1 ? index / (lines.length - 1) : 0;
    const hasCurrency = /€|\bEUR\b/i.test(line);
    const selectedMatches = isLabelled ? [matches[matches.length - 1]] : matches;

    for (const match of selectedMatches) {
      const amount = parseReceiptAmount(match[0]);
      if (!Number.isFinite(amount) || amount <= 0 || amount > 100000) continue;
      if (/%/.test(line.slice(match.index + match[0].length, match.index + match[0].length + 3))) continue;
      if (/\b\d{1,2}[./-]\d{1,2}[./-]\d{2,4}\b/.test(line) && !isLabelled) continue;

      const candidate = {
        amount,
        line,
        labelled: isLabelled,
        score: isLabelled
          ? 12 + position * 2 + (hasCurrency ? 1 : 0) - (isExcluded ? 8 : 0)
          : 1 + position * 3 + (hasCurrency ? 1.5 : 0),
      };

      if (isLabelled) {
        if (!isExcluded) labelled.push(candidate);
      } else if (!isExcluded && position >= 0.45) {
        fallback.push(candidate);
      }
    }
  });

  const ranked = labelled.length ? labelled : fallback;
  const bestByAmount = new Map();
  for (const candidate of ranked) {
    const cents = Math.round(candidate.amount * 100);
    const current = bestByAmount.get(cents);
    if (!current || candidate.score > current.score) bestByAmount.set(cents, candidate);
  }

  return [...bestByAmount.values()].sort((a, b) => b.score - a.score).slice(0, 5);
}

function parseReceiptAmount(raw) {
  const value = raw.replace(/€|EUR/gi, '').replace(/\s/g, '');
  const decimalSeparator = Math.max(value.lastIndexOf(','), value.lastIndexOf('.'));
  if (decimalSeparator < 0) return NaN;
  const integer = value.slice(0, decimalSeparator).replace(/[.,]/g, '');
  const fraction = value.slice(decimalSeparator + 1);
  if (!/^\d+$/.test(integer) || !/^\d{2}$/.test(fraction)) return NaN;
  return Number(`${integer}.${fraction}`);
}

function showResult(text, confidence, candidates) {
  const best = candidates[0];
  resultCard.hidden = false;
  candidateList.replaceChildren();
  copyFeedback.textContent = '';
  ocrText.textContent = text.trim() || 'Kein lesbarer Text gefunden.';
  ocrDetails.hidden = !text.trim();

  if (!best) {
    resultTitle.textContent = 'Keine klare Summe gefunden';
    resultHint.textContent = 'Du kannst den Betrag hier selbst eintragen. Ein schärferes, gerades Foto hilft oft.';
    matchBadge.textContent = 'Bitte selbst prüfen';
    amountInput.value = '';
    amountInput.focus({ preventScroll: true });
    candidateArea.hidden = true;
    sourceLine.hidden = true;
    resultCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    return;
  }

  resultTitle.textContent = 'Gefundene Summe';
  resultHint.textContent = confidence < 45
    ? 'Die Schrift war schwer zu lesen. Bitte vergleiche den Vorschlag mit dem Bon.'
    : 'Bitte prüfe den Betrag kurz, bevor du ihn übernimmst.';
  matchBadge.textContent = best.labelled ? 'Gesamtbetrag erkannt' : 'Möglicher Betrag';
  amountInput.value = formatDecimal(best.amount);
  sourceText.textContent = best.line;
  sourceLine.hidden = false;

  const alternatives = candidates.slice(1, 4);
  candidateArea.hidden = alternatives.length === 0;
  for (const candidate of alternatives) {
    const button = document.createElement('button');
    button.className = 'candidate-chip';
    button.type = 'button';
    button.setAttribute('aria-pressed', 'false');
    button.title = candidate.line;
    button.innerHTML = `<strong>${escapeHtml(formatCurrency(candidate.amount))}</strong><span>${candidate.labelled ? 'Summe' : 'Betrag'}</span>`;
    button.addEventListener('click', () => {
      amountInput.value = formatDecimal(candidate.amount);
      sourceText.textContent = candidate.line;
      matchBadge.textContent = candidate.labelled ? 'Gesamtbetrag erkannt' : 'Möglicher Betrag';
      for (const chip of candidateList.querySelectorAll('.candidate-chip')) chip.setAttribute('aria-pressed', String(chip === button));
      copyFeedback.textContent = '';
    });
    candidateList.append(button);
  }
  resultCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

async function copyAmount() {
  const amount = parseManualAmount(amountInput.value);
  if (!Number.isFinite(amount) || amount <= 0) {
    copyFeedback.textContent = 'Bitte gib zuerst einen gültigen Betrag ein.';
    amountInput.focus();
    return;
  }

  const value = formatDecimal(amount);
  try {
    await navigator.clipboard.writeText(value);
    copyFeedback.textContent = `${formatCurrency(amount)} kopiert.`;
  } catch {
    amountInput.select();
    copyFeedback.textContent = 'Betrag markiert – du kannst ihn jetzt kopieren.';
  }
}

function parseManualAmount(raw) {
  const value = raw.trim().replace(/€|EUR/gi, '').replace(/\s/g, '');
  if (!value) return NaN;
  const comma = value.lastIndexOf(',');
  const dot = value.lastIndexOf('.');
  let normalized = value;
  if (comma >= 0 && dot >= 0) {
    const decimal = comma > dot ? ',' : '.';
    normalized = value.replace(decimal === ',' ? /\./g : /,/g, '').replace(decimal, '.');
  } else if (comma >= 0) {
    normalized = value.replace(/\./g, '').replace(',', '.');
  } else if (/^\d{1,3}\.\d{3}$/.test(value)) {
    normalized = value.replace('.', '');
  } else if ((value.match(/\./g) ?? []).length > 1) {
    const last = value.lastIndexOf('.');
    normalized = `${value.slice(0, last).replace(/\./g, '')}.${value.slice(last + 1)}`;
  }
  return /^\d+(?:\.\d{1,2})?$/.test(normalized) ? Number(normalized) : NaN;
}

function formatDecimal(amount) {
  return amount.toFixed(2).replace('.', ',');
}

function formatCurrency(amount) {
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(amount);
}

function formatFileSize(bytes) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toLocaleString('de-DE', { maximumFractionDigits: 1 })} MB`;
}

function updateProgress(label, percent) {
  progressLabel.textContent = label;
  progressPercent.textContent = `${Math.min(100, Math.max(0, percent))}%`;
  progressFill.style.width = `${Math.min(100, Math.max(0, percent))}%`;
}

function showError(message) {
  errorMessage.textContent = message;
  errorMessage.hidden = false;
}

function resetScanner() {
  clearObjectUrl();
  selectedFile = null;
  preview.removeAttribute('src');
  uploadState.hidden = false;
  previewState.hidden = true;
  scanActions.hidden = true;
  progressCard.hidden = true;
  resultCard.hidden = true;
  errorMessage.hidden = true;
  candidateArea.hidden = true;
  sourceLine.hidden = true;
  ocrDetails.hidden = true;
  amountInput.value = '';
  copyFeedback.textContent = '';
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function clearObjectUrl() {
  if (previewUrl) URL.revokeObjectURL(previewUrl);
  previewUrl = null;
}

function escapeHtml(value) {
  return value.replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
}
