window.name = "NG_DEFER_BOOTSTRAP!";

require([
  "angular",
  "diff2html",
  "diff",
  "bootstrap",
  "angular-highlightjs-min",
  "highlightjs-lang-java-min",
  "css!bootstrap-css",
  "css!diff2html-css",
  "css!highlightjs-style-github-min",
  "css!app-css"
], function(angular, Diff2Html, diff) {

  angular.module("myApp", ["hljs"])
    .controller("OrgController", function($scope, $http, $sce) {

      $http.defaults.headers.common["X-ENCRYPTED-OWNER-ID"] = window.encOwnerId;

      $scope.orgs = [];
      $scope.selectedOrgs = [];
      $scope.viewOrgData = {};
      $scope.fromOrgData = {};
      $scope.toOrgData = {};
      $scope.diffData = {};
      $scope.pos = {isSelectAll: false};
      $scope.isGithubReady = false;
      $scope.githubRepos = [];


      $scope.mergeAll = function() {
        var all = true;
        angular.forEach($scope.diffData.apexclasses, function(value) {
          if (!value.selectedForMerge) {
            all = false;
          }
        });
        return all;
      };

      $scope.hasData = function(data) {
        if (angular.isObject(data)) {
          return Object.keys(data).length;
        }
        else {
          return false;
        }
      };

      $scope.updateSelection = function() {
        angular.forEach($scope.diffData.apexclasses, function(value) {
          value.selectedForMerge = $scope.pos.isSelectAll;
        });
      };

      $scope.$watchCollection(function() {
        return [$scope.fromOrgData, $scope.toOrgData];
      }, function() {
        if (($scope.fromOrgData.apexclasses === undefined) || ($scope.toOrgData.apexclasses === undefined)) {
          return;
        }

        var apexclasses = {};

        function defaultApexClass() {
          return {
            from: {
              Body: null
            },
            to: {
              Body: null
            }
          };
        }

        $scope.fromOrgData.apexclasses.forEach(function(item) {
          if (apexclasses[item.Name] === undefined) {
            apexclasses[item.Name] = defaultApexClass();
          }
          apexclasses[item.Name].from = item;
        });

        $scope.toOrgData.apexclasses.forEach(function(item) {
          if (apexclasses[item.Name] === undefined) {
            apexclasses[item.Name] = defaultApexClass();
          }
          apexclasses[item.Name].to = item;
        });

        // remove matching classes and set properties that indicate create, update, or delete
        angular.forEach(apexclasses, function(apexClass, apexClassName) {
          if ((apexClass.to.Body === null) && (apexClass.from.Body !== null)) {
            apexClass.create = true;
          }
          else if ((apexClass.to.Body !== null) && (apexClass.from.Body !== null) && (apexClass.to.Body != apexClass.from.Body)) {
            apexClass.update = true;
          }
          else if ((apexClass.to.Body !== null) && (apexClass.from.Body !== null) && (apexClass.to.Body == apexClass.from.Body)) {
            delete apexclasses[apexClassName];
          }
          else if ((apexClass.to.Body !== null) && (apexClass.from.Body === null)) {
            apexClass.delete = true;
          }
        });

        // calculate diffs
        for (var apexClassKey in apexclasses) {
          if (apexclasses.hasOwnProperty(apexClassKey)) {
            var apexClass = apexclasses[apexClassKey];

            var patchText = diff.createPatch(apexClassKey, apexClass.to.Body, apexClass.from.Body);
            patchText = "diff --git a/" + apexClassKey + " b/" + apexClassKey + "\n" + patchText;

            var diffHtml = Diff2Html.getPrettySideBySideHtmlFromDiff(patchText);

            var e = angular.element(diffHtml);

            apexclasses[apexClassKey].diff = $sce.trustAsHtml(e.html());
          }
        }

        $scope.diffData.apexclasses = apexclasses;
      });

      function fetchOrgs() {
        $http
          .get("/orgs")
          .success(function(data, status, headers, config) {
            $scope.orgs = data;
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      }

      $scope.fetchOrgData = function(id, callback) {
        $http
          .get("/orgs/" + id + "/metadata")
          .success(function(data, status, headers, config) {
            callback(data);
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      };

      $scope.selectOrg = function(org) {
        var id = org.id;

        var i = $scope.selectedOrgs.indexOf(id);

        if (i >= 0) {
          // unselect
          $scope.selectedOrgs.splice(i, 1);
        }
        else if ($scope.selectedOrgs.length < 2) {
          // add selection
          $scope.selectedOrgs.push(id);
        }
        else if ($scope.selectedOrgs.length == 2) {
          // replace selection
          $scope.selectedOrgs.pop();
          $scope.selectedOrgs.push(id);
        }

        if ($scope.selectedOrgs.length == 1) {
          $scope.viewOrgData = org;
          $scope.fromOrgData = org;
          $scope.toOrgData = {};
          $scope.diffData = {};
          $scope.fetchOrgData($scope.selectedOrgs[0], function(data) {
            $scope.viewOrgData = data;
            $scope.fromOrgData = data;
          });
        }
        else if ($scope.selectedOrgs.length == 2) {
          $scope.toOrgData = org;
          $scope.fetchOrgData($scope.selectedOrgs[1], function(data) {
            $scope.toOrgData = data;
          });
        }
      };

      $scope.flipSelected = function() {
        var from = $scope.selectedOrgs[0];
        $scope.selectedOrgs[0] = $scope.selectedOrgs[1];
        $scope.selectedOrgs[1] = from;

        var fromData = $scope.fromOrgData;
        $scope.fromOrgData = $scope.toOrgData;
        $scope.toOrgData = fromData;
      };

      $scope.getActionLabel = function(apexClass) {
        if (apexClass.create) {
          return "Create";
        }
        else if (apexClass.update) {
          return "Update";
        }
        else if (apexClass.delete) {
          return "Delete";
        }
      };

      $scope.forceMergeAll = function() {
        $scope.forceMergeSelected();
      };

      $scope.forceMergeSelected = function() {
        var apexClasses = {
          creates: {},
          updates: {},
          deletes: []
        };

        angular.forEach($scope.diffData.apexclasses, function(apexClass) {
          if (apexClass.selectedForMerge) {
            if (apexClass.create) {
              apexClasses.creates[apexClass.from.Name] = apexClass.from.Body;
            }
            else if (apexClass.update) {
              apexClasses.updates[apexClass.to.Id] = apexClass.from.Body;
            }
            else if (apexClass.delete) {
              apexClasses.deletes.push(apexClass.to.Id);
            }
          }
        });

        var url = "/orgs/" + $scope.toOrgData.id + "/metadata";

        $http
          .post(url, { apexClasses: apexClasses })
          .success(function(data, status, headers, config) {
            $scope.diffData = {};
            $scope.fetchOrgData($scope.selectedOrgs[1], function(data) {
              $scope.toOrgData = data;
            });
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      };

      $scope.syncToGitHub = function() {

      };

      function getGitHubReadyStatus() {
        $http
          .get("/github/status")
          .success(function(data) {
            $scope.isGithubReady = data.isReady;
            if ($scope.isGithubReady) {
              fetchGitHubRepos();
            }
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      }

      function fetchGitHubRepos() {
        $http
          .get("/github/repos")
          .success(function(data) {
            $scope.githubRepos = data;
          })
          .error(function(error) {
            console.log(error);
            // todo
          });
      }


      // init
      fetchOrgs();
      getGitHubReadyStatus();

    });

  angular.element(document).ready(function() {
    angular.resumeBootstrap();
  });

});