Fdir = '';

fn = fullfile(Fdir, 'igdd_evaluation_with pool and score.xlsx');

T = readtable(fn, 'Sheet', 'Averages', 'VariableNamingRule', 'preserve');

levels = ["Level 1", "Level 2", "Level 3", "Level 4"];

% Level 1-4
T = T(strcmp(string(T.Scope), "Workbook") & ismember(string(T.Level), levels), :);

[~, loc] = ismember(levels, string(T.Level));
T = T(loc, :);

% Define figure size
figureWidth = 1000; 
figureHeight = 750; 

fig = figure('Position', [100, 100, figureWidth, figureHeight]);

% Define position of the axes
axesPosition = [0.28, 0.3, 0.7, 0.57]; 
axes('Position', axesPosition);

% X axis labels
x = {'Level 1', 'Level 2', 'Level 3', 'Level 4'};

% ----------- Recall data -----------
y1 = T.("Recall_IGDD(D)-G&A")' * 100;    % IGDD(D)-G&A
y2 = T.("Recall_Data.gov")' * 100;       % Data.gov
y3 = T.("Recall_BM25")' * 100;           % BM25

% ----------- Difference -----------
diff_y1_y2 = y1 - y2;    % IGDD(D)-G&A - Data.gov
diff_y1_y3 = y1 - y3;    % IGDD(D)-G&A - BM25

diff_all = [diff_y1_y2(:), diff_y1_y3(:)];

% ----------- Bar plot differences -----------
hold on;
b = bar(1:numel(x), diff_all, 0.7, 'grouped');

b(1).FaceColor = [0 0.4 0.7];
b(1).FaceAlpha = 0.45;

b(2).FaceColor = [0.8 0.3 0.1];
b(2).FaceAlpha = 0.45;

% ----------- Line plots -----------
defaultColors = get(groot,'defaultAxesColorOrder');

p1 = plot(1:numel(x), y1, '--s', ...
    'LineWidth', 3, ...
    'Color', defaultColors(2,:), ...
    'MarkerSize', 9);

p2 = plot(1:numel(x), y2, '-.v', ...
    'LineWidth', 3, ...
    'Color', defaultColors(5,:), ...
    'MarkerSize', 9);

p3 = plot(1:numel(x), y3, '-->', ...
    'LineWidth', 3, ...
    'Color', defaultColors(6,:), ...
    'MarkerSize', 10);

title('Recall@20 Performance');
ylabel('Recall@20 (%)');

% Set x-axis ticks
xticks(1:numel(x));
xticklabels(x);

% Set x-axis limit
xlim([0.5, 4.5]);

% Set y-axis limit
ylim([0 100]);

set(gca, 'FontName', 'Times New Roman', 'FontSize', 18);

% Vertical lines
line([0.5 0.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth', 2);
line([1.5 1.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth', 2);
line([2.5 2.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth', 2);
line([3.5 3.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth', 2);
line([4.5 4.5], ylim, 'Color', [0.7, 0.7, 0.7], 'LineWidth', 2);

% Horizontal line at y=100
line(xlim, [100 100], 'Color', [0.7, 0.7, 0.7], 'LineWidth', 2);

% Legend
legend([p1 p2 p3 b(1) b(2)], ...
       {'IGDD(D)-G&A', 'Data.gov', 'BM25', ...
        'Difference (Data.gov)', 'Difference (BM25)'}, ...
       'Location', [0, 0.02, 0.28, 0.22]);

hold off;