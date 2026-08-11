% Define figure size
figureWidth = 1000;
figureHeight = 750;

fig = figure('Position', [100, 100, figureWidth, figureHeight]);

% Define position of the axes
axesPosition = [0.2, 0.37, 0.7, 0.57];
axes('Position', axesPosition);

% X axis labels
x = {'Level 1', 'Level 2', 'Level 3', 'Level 4'};

% ----------- Your NDCG data -----------
Fdir = '';

fn = fullfile(Fdir, 'igdd_evaluation_with pool and score.xlsx');

T = readtable(fn, 'Sheet', 'Averages', 'VariableNamingRule', 'preserve');

levels = ["Level 1", "Level 2", "Level 3", "Level 4"];

% Level 1-4
T = T(strcmp(string(T.Scope), "Workbook") & ismember(string(T.Level), levels), :);

% 按 Level 1-4 排序
[~, loc] = ismember(levels, string(T.Level));
T = T(loc, :);

% ----------- Read NDCG data -----------
y1 = T.("NDCG_IGDD-G&A")' * 100;       % IGDD-G&A
y2 = T.("NDCG_IGDD(D)-G&A")' * 100;    % IGDD(D)-G&A
y3 = T.("NDCG_IGDD-G")' * 100;         % IGDD-G
y4 = T.("NDCG_IGDD(D)-G")' * 100;      % IGDD(D)-G
y5 = T.("NDCG_Data.gov")' * 100;       % Data.gov
y6 = T.("NDCG_BM25")' * 100;           % BM25

% ----------- Plot lines -----------
plot(1:numel(x), y1, '-o', 'LineWidth',3);
hold on;
plot(1:numel(x), y2, '--s', 'LineWidth',3);
hold on;
plot(1:numel(x), y3, '-.^', 'LineWidth',3);
hold on;
plot(1:numel(x), y4, ':d', 'LineWidth',3);
hold on;
plot(1:numel(x), y5, '-.v', 'LineWidth',3);
hold on;
plot(1:numel(x), y6, '-->', 'LineWidth',3);

title('NDCG@10 Performance');

ylabel('NDCG@10 (%)');

% Set x-axis ticks
xticks(1:numel(x));
xticklabels(x);

% Set x-axis limit
xlim([0.5, 4.5]);

% Set y-axis limit
ylim([0 100]);

set(gca, 'FontName', 'Times New Roman', 'FontSize', 18);

% Vertical lines
hold on;
line([0.5 0.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth',2);
line([1.5 1.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth',2);
line([2.5 2.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth',2);
line([3.5 3.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth',2);
line([4.5 4.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth',2);
hold off;

legend('IGDD-G&A', 'IGDD(D)-G&A', 'IGDD-G', 'IGDD(D)-G', 'Data.gov', 'BM25', ...
    'Location', [0, 0, 0.2, 0.3]);